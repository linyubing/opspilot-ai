package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.GoldForecastRule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** 固定正则下扩大真实OHLC训练历史的只读研究，不发布正式模型。 */
public class HistoryRun {
    static final Path STATE = Path.of("../docs/research/2026-10-02-state-check.json");
    static final Path RIDGE = Path.of("../docs/research/2026-10-02-ridge-check.json");
    static final double LAMBDA = .01;
    record Input(LocalDate date, LocalDate target, String actual, double[] x) {}
    record Row(LocalDate date, LocalDate target, String actual, double[] candidate,
               double[] reference, double[] oldPrior, double[] newPrior) {}
    record Scores(ForecastMetrics all, ForecastMetrics signals) {}
    record Fold(LocalDate start, LocalDate end, LocalDate trainStart, LocalDate trainEnd,
                LocalDate trainTargetEnd, int trainingCount, List<LocalDate> trainingDates,
                List<LocalDate> pitTrainingDates, double[] mean, double[] std,
                double[] pitMean, double[] pitStd, double[] referenceWeights,
                Map<String, Integer> trainLabels, Map<String, Integer> pitLabels,
                SoftmaxFit.Fit fit, Scores training, Scores validation,
                ForecastMetrics oldPriorOnSignals, ForecastMetrics newPriorOnSignals,
                List<LocalDate> selectedDates, List<Row> predictions,
                double repeatDifference, double referenceDifference) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要新结果路径和完整Git哈希");
        Path output = Path.of(args[0]);
        if (Files.exists(output)) throw new IllegalArgumentException("不覆盖已有研究结果");
        HistoryChecks.main(new String[0]);
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode state = json.readTree(STATE.toFile()), ridge = json.readTree(RIDGE.toFile());
        if (!state.path("version").asText().equals("fred-price-state-audit-v1")
                || !ridge.path("version").asText().equals("fred-centered-ridge-v1")
                || state.path("promotionAllowed").asBoolean() || ridge.path("promotionAllowed").asBoolean()
                || !state.path("referenceHash").asText().equals(fileHash(RIDGE)))
            throw new IllegalStateException("冻结参照来源变化");
        checkSources(state); checkSources(ridge);
        var bars = TrainingProbe.readBars(); String barHash = TrainingProbe.hashBars(bars);
        if (bars.size() != 4382 || !barHash.equals(state.path("barHash").asText())
                || !barHash.equals(ridge.path("barHash").asText()))
            throw new IllegalStateException("真实日线数量或指纹变化");
        var calculator = new GoldFeatureCalculator(); var rule = new GoldForecastRule();
        List<TrainingProbe.Row> inputs = new ArrayList<>();
        for (int i = 20; i + 1 < bars.size(); i++) {
            var base = bars.get(i); var target = bars.get(i + 1);
            var features = calculator.compute(base.priceDate(), bars.subList(i - 20, i + 1)).orElseThrow();
            double[] x = TrainingProbe.NAMES.stream().mapToDouble(n -> features.values().get(n)).toArray();
            var change = target.close().divide(base.close(), MathContext.DECIMAL128)
                    .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            inputs.add(new TrainingProbe.Row(base.priceDate(), target.priceDate(), x, rule.classify(change)));
        }
        if (inputs.size() != 4361) throw new IllegalStateException("真实20维样本数变化");
        Map<LocalDate, TrainingProbe.Row> byDate = new LinkedHashMap<>();
        for (var row : inputs) if (byDate.put(row.date(), row) != null) throw new IllegalStateException("样本日期重复");
        List<Fold> folds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JsonNode old = state.path("folds").get(i), oldRidge = ridge.path("folds").get(i);
            LocalDate start = LocalDate.parse(old.path("start").asText()), end = LocalDate.parse(old.path("end").asText());
            var train = HistorySlice.training(inputs, start);
            List<TrainingProbe.Row> pit = new ArrayList<>(), validation = new ArrayList<>();
            for (var saved : old.path("training")) {
                var row = matching(byDate, saved); JsonNode in = saved.path("input");
                for (var pair : Map.of("volatility20", "volatility", "overnightGap", "gap", "return1", "return1", "return20", "return20").entrySet())
                    if (Math.abs(row.x()[TrainingProbe.NAMES.indexOf(pair.getKey())] - in.path(pair.getValue()).asDouble()) > 1e-12)
                        throw new IllegalStateException("参照真实训练特征不一致");
                pit.add(row);
            }
            if (!HistorySlice.training(pit, start).equals(pit) || pit.size() != 779 + i * 240)
                throw new IllegalStateException("冻结训练时间范围变化");
            for (var saved : old.path("predictions")) validation.add(matching(byDate, saved));
            if (train.size() != 3640 + i * 240 || validation.size() != 240
                    || !validation.getFirst().date().equals(start) || !validation.getLast().date().equals(end)
                    || !validation.getLast().target().isBefore(TrainingProbe.LIMIT))
                throw new IllegalStateException("固定分区或扩大训练数量变化");
            var scale = new TrainingProbe.Scale(train); var pitScale = new TrainingProbe.Scale(pit);
            JsonNode frozen = null;
            for (var result : oldRidge.path("results"))
                if (result.path("profile").asText().equals("OHLC_20") && result.path("lambda").asDouble() == LAMBDA) frozen = result;
            if (frozen == null || !frozen.path("fit").path("converged").asBoolean()) throw new IllegalStateException("冻结模型缺失");
            double[] oldWeights = json.convertValue(frozen.path("fit").path("weights"), double[].class);
            double[] oldPrior = prior(pit), newPrior = prior(train);
            double referenceDifference = 0;
            for (int j = 0; j < validation.size(); j++) {
                var saved = old.path("predictions").get(j); var p = SoftmaxFit.probabilities(oldWeights, pitScale.values(validation.get(j)));
                var q = json.convertValue(saved.path("reference"), double[].class);
                referenceDifference = Math.max(referenceDifference, TrainingProbe.distance(p, q));
                if (TrainingProbe.distance(oldPrior, json.convertValue(saved.path("prior"), double[].class)) > 1e-12)
                    throw new IllegalStateException("旧训练频率不一致");
            }
            if (referenceDifference > 1e-12) throw new IllegalStateException("冻结20维模型无法复现");
            double[][] x = train.stream().map(scale::values).toArray(double[][]::new);
            int[] y = train.stream().mapToInt(row -> row.label().ordinal()).toArray();
            var fit = RidgeFit.fit(x, y, LAMBDA); var repeat = RidgeFit.fit(x, y, LAMBDA);
            if (!fit.converged() || !repeat.converged()) throw new IllegalStateException("扩大训练求解未收敛");
            Function<TrainingProbe.Row, double[]> predict = row -> SoftmaxFit.probabilities(fit.weights(), scale.values(row));
            List<Row> rows = new ArrayList<>(); List<TrainingProbe.Row> selected = new ArrayList<>(); double difference = 0;
            for (int j = 0; j < validation.size(); j++) {
                var row = validation.get(j); var p = predict.apply(row);
                difference = Math.max(difference, TrainingProbe.distance(p, SoftmaxFit.probabilities(repeat.weights(), scale.values(row))));
                if (Arrays.stream(p).max().orElseThrow() >= .55) selected.add(row);
                rows.add(new Row(row.date(), row.target(), row.label().name(), p,
                        json.convertValue(old.path("predictions").get(j).path("reference"), double[].class), oldPrior.clone(), newPrior.clone()));
            }
            if (difference > 1e-12) throw new IllegalStateException("重复训练概率不一致");
            folds.add(new Fold(start, end, train.getFirst().date(), train.getLast().date(), train.getLast().target(),
                    train.size(), train.stream().map(TrainingProbe.Row::date).toList(), pit.stream().map(TrainingProbe.Row::date).toList(),
                    scale.mean, scale.std, pitScale.mean, pitScale.std, oldWeights, TrainingProbe.counts(train), TrainingProbe.counts(pit),
                    fit, score(train, predict, scale), score(validation, predict, scale),
                    selected.isEmpty() ? null : score(selected, row -> oldPrior, scale).all(),
                    selected.isEmpty() ? null : score(selected, row -> newPrior, scale).all(),
                    selected.stream().map(TrainingProbe.Row::date).toList(), rows, difference, referenceDifference));
            System.out.println(start + " train=" + train.size() + " validationAccuracy=" + folds.getLast().validation().all().accuracy());
        }
        List<Input> savedInputs = inputs.stream().map(row -> new Input(row.date(), row.target(), row.label().name(), row.x())).toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "ohlc-full-history-ridge-v1"); report.put("gitCommit", args[1]); report.put("createdAt", OffsetDateTime.now());
        report.put("barHash", barHash); report.put("barCount", bars.size()); report.put("inputHash", inputHash(savedInputs));
        report.put("featureNames", TrainingProbe.NAMES); report.put("featureVersion", GoldOhlcFeatures.VERSION);
        report.put("ruleVersion", GoldForecastRule.RULE_VERSION); report.put("lambda", LAMBDA); report.put("signalThreshold", .55);
        report.put("cutoffExclusive", TrainingProbe.LIMIT); report.put("promotionAllowed", false);
        report.put("stateHash", fileHash(STATE)); report.put("ridgeHash", fileHash(RIDGE));
        report.put("scope", "真实OHLC-only；原宏观PIT仅标识冻结参照；内部旧验证，非新盲测，无黄金日线历史修订可得性证明");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : List.of("HistorySlice.java", "HistoryChecks.java", "HistoryRun.java", "RidgeFit.java", "SoftmaxFit.java", "TrainingProbe.java"))
            hashes.put(file, TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/" + file))));
        report.put("sourceHashes", hashes);
        report.put("protocolHash", TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-02-history-protocol.md"))));
        report.put("inputs", savedInputs); report.put("folds", folds);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("REPORT " + output);
    }
    static TrainingProbe.Row matching(Map<LocalDate, TrainingProbe.Row> rows, JsonNode saved) {
        var row = rows.get(LocalDate.parse(saved.path("date").asText()));
        if (row == null || !row.target().toString().equals(saved.path("target").asText()) || !row.label().name().equals(saved.path("actual").asText()))
            throw new IllegalStateException("参照日期、目标或标签不一致");
        return row;
    }
    static double[] prior(List<TrainingProbe.Row> rows) {
        double[] p = new double[3]; for (var row : rows) p[row.label().ordinal()] += 1.0 / rows.size(); return p;
    }
    static Scores score(List<TrainingProbe.Row> rows, Function<TrainingProbe.Row, double[]> predict, TrainingProbe.Scale scale) {
        var score = TrainingProbe.score(rows, predict, scale); return new Scores(score.all(), score.signals());
    }
    static void checkSources(JsonNode report) throws Exception {
        for (var entry : report.path("sourceHashes").properties())
            if (!entry.getValue().asText().equals(TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/" + entry.getKey())))))
                throw new IllegalStateException("参照源码摘要变化");
    }
    static String fileHash(Path file) throws Exception {
        return TrainingProbe.sha(Files.readString(file).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
    }
    /** 浮点使用IEEE754大端字节，Java与Node可逐位复核，不依赖小数格式。 */
    static String inputHash(List<Input> rows) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var name : TrainingProbe.NAMES) digest.update((name + '\0').getBytes(StandardCharsets.UTF_8));
        for (var row : rows) {
            digest.update((row.date() + "\0" + row.target() + "\0" + row.actual() + "\0").getBytes(StandardCharsets.UTF_8));
            for (double value : row.x()) digest.update(ByteBuffer.allocate(8).putDouble(value).array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
