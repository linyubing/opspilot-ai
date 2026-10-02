package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.GoldForecastRule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** 固定ATR比例表达的真实历史实验；只读、不更改生产特征。 */
public class AtrRun {
    static final Path REFERENCE = Path.of("../docs/research/2026-10-02-history-check.json");
    record Price(LocalDate date, double close) {}
    record Bar(LocalDate date, String open, String high, String low, String close) {}
    record Row(LocalDate date, LocalDate target, String actual, double[] candidate,
               double[] reference, double[] pitReference, double[] prior) {}
    record Fold(LocalDate start, LocalDate end, LocalDate trainStart, LocalDate trainTargetEnd,
                int trainingCount, List<LocalDate> trainingDates, double[] mean, double[] std,
                Map<String, Integer> trainLabels, SoftmaxFit.Fit fit,
                HistoryRun.Scores training, HistoryRun.Scores validation,
                ForecastMetrics priorOnSignals, List<LocalDate> selectedDates,
                List<Row> predictions, double repeatDifference, double referenceDifference) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) throw new IllegalArgumentException("需要新结果路径和完整Git哈希");
        Path output = Path.of(args[0]); if (Files.exists(output)) throw new IllegalArgumentException("不覆盖已有结果");
        AtrChecks.main(new String[0]); HistoryChecks.main(new String[0]);
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var reference = json.readTree(REFERENCE.toFile());
        if (!reference.path("version").asText().equals("ohlc-full-history-ridge-v1")
                || reference.path("promotionAllowed").asBoolean()) throw new IllegalStateException("参照来源变化");
        HistoryRun.checkSources(reference);
        var bars = TrainingProbe.readBars(); String barHash = TrainingProbe.hashBars(bars);
        if (bars.size() != 4382 || !barHash.equals(reference.path("barHash").asText())) throw new IllegalStateException("真实日线摘要变化");
        var calculator = new GoldFeatureCalculator(); var rule = new GoldForecastRule();
        List<TrainingProbe.Row> raw = new ArrayList<>(), normalized = new ArrayList<>(); List<Price> prices = new ArrayList<>();
        for (int i = 20; i + 1 < bars.size(); i++) {
            var base = bars.get(i); var target = bars.get(i + 1);
            var features = calculator.compute(base.priceDate(), bars.subList(i - 20, i + 1)).orElseThrow();
            double[] x = TrainingProbe.NAMES.stream().mapToDouble(n -> features.values().get(n)).toArray();
            var change = target.close().divide(base.close(), MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            var label = rule.classify(change); double close = base.close().doubleValue();
            raw.add(new TrainingProbe.Row(base.priceDate(), target.priceDate(), x, label));
            normalized.add(new TrainingProbe.Row(base.priceDate(), target.priceDate(), AtrScale.apply(x, close), label));
            prices.add(new Price(base.priceDate(), close));
        }
        List<HistoryRun.Input> rawInputs = inputs(raw), savedInputs = inputs(normalized);
        if (raw.size() != 4361 || !HistoryRun.inputHash(rawInputs).equals(reference.path("inputHash").asText()))
            throw new IllegalStateException("原完整特征输入与参照不一致");
        for (int i = 0; i < raw.size(); i++) {
            var before = reference.path("inputs").get(i); var row = raw.get(i);
            if (!before.path("date").asText().equals(row.date().toString()) || !before.path("target").asText().equals(row.target().toString())
                    || !before.path("actual").asText().equals(row.label().name())
                    || !Arrays.equals(row.x(), json.convertValue(before.path("x"), double[].class)))
                throw new IllegalStateException("原输入不能逐值复现");
        }
        List<Fold> folds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var old = reference.path("folds").get(i);
            LocalDate start = LocalDate.parse(old.path("start").asText()), end = LocalDate.parse(old.path("end").asText());
            var train = HistorySlice.training(normalized, start); var rawTrain = HistorySlice.training(raw, start);
            var validation = normalized.stream().filter(r -> !r.date().isBefore(start) && !r.date().isAfter(end)).toList();
            var rawValidation = raw.stream().filter(r -> !r.date().isBefore(start) && !r.date().isAfter(end)).toList();
            if (train.size() != 3640 + i * 240 || validation.size() != 240 || !validation.getLast().target().isBefore(TrainingProbe.LIMIT))
                throw new IllegalStateException("固定时间分区变化");
            var scale = new TrainingProbe.Scale(train); var rawScale = new TrainingProbe.Scale(rawTrain);
            double[] oldWeights = json.convertValue(old.path("fit").path("weights"), double[].class), prior = HistoryRun.prior(train);
            double referenceDifference = 0;
            for (int j = 0; j < validation.size(); j++) {
                var row = validation.get(j); var saved = old.path("predictions").get(j);
                if (!row.date().toString().equals(saved.path("date").asText()) || !row.target().toString().equals(saved.path("target").asText())
                        || !row.label().name().equals(saved.path("actual").asText())) throw new IllegalStateException("参照日期或标签变化");
                referenceDifference = Math.max(referenceDifference, TrainingProbe.distance(
                        SoftmaxFit.probabilities(oldWeights, rawScale.values(rawValidation.get(j))), json.convertValue(saved.path("candidate"), double[].class)));
                if (TrainingProbe.distance(prior, json.convertValue(saved.path("newPrior"), double[].class)) > 1e-12)
                    throw new IllegalStateException("训练类别频率变化");
            }
            if (referenceDifference > 1e-12) throw new IllegalStateException("冻结全历史模型不能复现");
            double[][] x = train.stream().map(scale::values).toArray(double[][]::new); int[] y = train.stream().mapToInt(r -> r.label().ordinal()).toArray();
            var fit = RidgeFit.fit(x, y, .01); var repeat = RidgeFit.fit(x, y, .01);
            if (!fit.converged() || !repeat.converged()) throw new IllegalStateException("ATR比例训练未收敛");
            Function<TrainingProbe.Row, double[]> predict = row -> SoftmaxFit.probabilities(fit.weights(), scale.values(row));
            List<Row> rows = new ArrayList<>(); List<TrainingProbe.Row> selected = new ArrayList<>(); double difference = 0;
            for (int j = 0; j < validation.size(); j++) {
                var row = validation.get(j); var p = predict.apply(row); var saved = old.path("predictions").get(j);
                difference = Math.max(difference, TrainingProbe.distance(p, SoftmaxFit.probabilities(repeat.weights(), scale.values(row))));
                if (Arrays.stream(p).max().orElseThrow() >= .55) selected.add(row);
                rows.add(new Row(row.date(), row.target(), row.label().name(), p,
                        json.convertValue(saved.path("candidate"), double[].class), json.convertValue(saved.path("reference"), double[].class), prior.clone()));
            }
            if (difference > 1e-12) throw new IllegalStateException("重复训练不一致");
            folds.add(new Fold(start, end, train.getFirst().date(), train.getLast().target(), train.size(), train.stream().map(TrainingProbe.Row::date).toList(),
                    scale.mean, scale.std, TrainingProbe.counts(train), fit, HistoryRun.score(train, predict, scale), HistoryRun.score(validation, predict, scale),
                    selected.isEmpty() ? null : HistoryRun.score(selected, row -> prior, scale).all(),
                    selected.stream().map(TrainingProbe.Row::date).toList(), rows, difference, referenceDifference));
            System.out.println(start + " accuracy=" + folds.getLast().validation().all().accuracy());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "ohlc-relative-atr-v1"); report.put("gitCommit", args[1]); report.put("createdAt", OffsetDateTime.now());
        report.put("barHash", barHash); report.put("barCount", bars.size()); report.put("rawInputHash", reference.path("inputHash").asText());
        report.put("inputHash", HistoryRun.inputHash(savedInputs)); report.put("referenceHash", HistoryRun.fileHash(REFERENCE));
        report.put("featureNames", TrainingProbe.NAMES); report.put("featureVersion", "ohlc-atr-close-percent-v1"); report.put("rawFeatureVersion", GoldOhlcFeatures.VERSION);
        report.put("ruleVersion", GoldForecastRule.RULE_VERSION); report.put("lambda", .01); report.put("signalThreshold", .55);
        report.put("cutoffExclusive", TrainingProbe.LIMIT); report.put("promotionAllowed", false);
        report.put("scope", "仅真实OHLC基准日ATR比例变换，内部旧验证非盲测，无黄金日线历史修订可得性证明");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : List.of("AtrScale.java", "AtrChecks.java", "AtrRun.java", "HistorySlice.java", "HistoryChecks.java", "HistoryRun.java", "TrainingProbe.java", "RidgeFit.java", "SoftmaxFit.java"))
            hashes.put(name, TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/" + name))));
        report.put("sourceHashes", hashes); report.put("protocolHash", TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-02-atr-protocol.md"))));
        // 原始十进制日线一并留档：独立核验可绑定分母，防止用目标日价格或未认证数值。
        report.put("bars", bars.stream().map(b -> new Bar(b.priceDate(), b.open().toPlainString(), b.high().toPlainString(),
                b.low().toPlainString(), b.close().toPlainString())).toList());
        report.put("prices", prices); report.put("inputs", savedInputs); report.put("folds", folds);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); System.out.println("REPORT " + output);
    }
    static List<HistoryRun.Input> inputs(List<TrainingProbe.Row> rows) {
        return rows.stream().map(row -> new HistoryRun.Input(row.date(), row.target(), row.label().name(), row.x())).toList();
    }
}
