package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.tribuo.Model;
import org.tribuo.classification.Label;
import org.tribuo.common.xgboost.XGBoostModel;

/** 完整真实20维OHLC的固定树模型对照；只读、仅研究，不晋级。 */
public class TreeRun {
    static final Path REFERENCE = Path.of("../docs/research/2026-10-02-history-check.json");
    record Bar(LocalDate date, String open, String high, String low, String close) {}
    record TrainRow(LocalDate date, LocalDate target, String actual, double[] candidate) {}
    record Row(LocalDate date, LocalDate target, String actual, double[] candidate, double[] reference,
               double[] pitReference, double[] prior) {}
    record NativeModel(List<String> featureOrder, List<String> labelOrder, JsonNode state) {}
    record Fold(LocalDate start, LocalDate end, LocalDate trainStart, LocalDate trainTargetEnd,
                int trainingCount, List<LocalDate> trainingDates, double[] mean, double[] std,
                Map<String, Integer> trainLabels, NativeModel model,
                HistoryRun.Scores training, HistoryRun.Scores validation,
                ForecastMetrics priorOnSignals, List<LocalDate> selectedDates,
                List<TrainRow> trainingPredictions, List<Row> predictions,
                double repeatDifference, double referenceDifference) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{40}")) throw new IllegalArgumentException("需要新结果路径和完整Git哈希");
        Path output = Path.of(args[0]); if (Files.exists(output)) throw new IllegalArgumentException("不覆盖已有研究结果");
        TreeChecks.main(new String[0]); HistoryChecks.main(new String[0]);
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode reference = json.readTree(REFERENCE.toFile());
        if (!reference.path("version").asText().equals("ohlc-full-history-ridge-v1") || reference.path("promotionAllowed").asBoolean())
            throw new IllegalStateException("冻结参照来源变化");
        HistoryRun.checkSources(reference);
        var bars = TrainingProbe.readBars(); String barHash = TrainingProbe.hashBars(bars);
        if (bars.size() != 4382 || !barHash.equals(reference.path("barHash").asText())) throw new IllegalStateException("真实日线摘要变化");
        var calculator = new GoldFeatureCalculator(); var rule = new GoldForecastRule();
        List<TrainingProbe.Row> inputs = new ArrayList<>();
        for (int i = 20; i + 1 < bars.size(); i++) {
            var base = bars.get(i); var target = bars.get(i + 1);
            var features = calculator.compute(base.priceDate(), bars.subList(i - 20, i + 1)).orElseThrow();
            double[] x = TrainingProbe.NAMES.stream().mapToDouble(n -> features.values().get(n)).toArray();
            var change = target.close().divide(base.close(), MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            inputs.add(new TrainingProbe.Row(base.priceDate(), target.priceDate(), x, rule.classify(change)));
        }
        var savedInputs = inputs.stream().map(r -> new HistoryRun.Input(r.date(), r.target(), r.label().name(), r.x())).toList();
        if (inputs.size() != 4361 || !HistoryRun.inputHash(savedInputs).equals(reference.path("inputHash").asText()))
            throw new IllegalStateException("原始20维输入摘要变化");
        for (int i = 0; i < inputs.size(); i++) {
            var row = inputs.get(i); var saved = reference.path("inputs").get(i);
            if (!row.date().toString().equals(saved.path("date").asText()) || !row.target().toString().equals(saved.path("target").asText())
                    || !row.label().name().equals(saved.path("actual").asText()) || !Arrays.equals(row.x(), json.convertValue(saved.path("x"), double[].class)))
                throw new IllegalStateException("冻结原输入无法逐值复现");
        }
        List<Fold> folds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var old = reference.path("folds").get(i);
            LocalDate start = LocalDate.parse(old.path("start").asText()), end = LocalDate.parse(old.path("end").asText());
            var train = HistorySlice.training(inputs, start);
            var validation = inputs.stream().filter(r -> !r.date().isBefore(start) && !r.date().isAfter(end)).toList();
            if (train.size() != 3640 + i * 240 || validation.size() != 240 || !validation.getLast().target().isBefore(TrainingProbe.LIMIT))
                throw new IllegalStateException("固定时间分区变化");
            var scale = new TrainingProbe.Scale(train); double[] prior = HistoryRun.prior(train);
            double[] weights = json.convertValue(old.path("fit").path("weights"), double[].class); double referenceDifference = 0;
            for (int j = 0; j < validation.size(); j++) {
                var row = validation.get(j); var saved = old.path("predictions").get(j);
                if (!row.date().toString().equals(saved.path("date").asText()) || !row.target().toString().equals(saved.path("target").asText())
                        || !row.label().name().equals(saved.path("actual").asText())) throw new IllegalStateException("参照验证日期或标签变化");
                referenceDifference = Math.max(referenceDifference, TrainingProbe.distance(SoftmaxFit.probabilities(weights, scale.values(row)), json.convertValue(saved.path("candidate"), double[].class)));
                if (TrainingProbe.distance(prior, json.convertValue(saved.path("newPrior"), double[].class)) > 1e-12)
                    throw new IllegalStateException("训练频率参照变化");
            }
            if (referenceDifference > 1e-12) throw new IllegalStateException("完整历史线性模型无法复现");
            var model = TreeFit.train(train, scale); var repeat = TreeFit.train(train, scale);
            var nativeModel = archive(model, json); var repeatedModel = archive(repeat, json);
            if (!nativeModel.equals(repeatedModel)) throw new IllegalStateException("固定种子原生模型不一致");
            Function<TrainingProbe.Row, double[]> predict = row -> TreeFit.probabilities(model, row, scale);
            List<Row> predictions = new ArrayList<>(); List<TrainingProbe.Row> selected = new ArrayList<>(); double difference = 0;
            for (int j = 0; j < validation.size(); j++) {
                var row = validation.get(j); double[] p = predict.apply(row); var saved = old.path("predictions").get(j);
                difference = Math.max(difference, TrainingProbe.distance(p, TreeFit.probabilities(repeat, row, scale)));
                if (Arrays.stream(p).max().orElseThrow() >= .55) selected.add(row);
                predictions.add(new Row(row.date(), row.target(), row.label().name(), p,
                        json.convertValue(saved.path("candidate"), double[].class), json.convertValue(saved.path("reference"), double[].class), prior.clone()));
            }
            if (difference > 1e-12) throw new IllegalStateException("重复训练概率不一致");
            var trainingPredictions = train.stream().map(r -> new TrainRow(r.date(), r.target(), r.label().name(), predict.apply(r))).toList();
            folds.add(new Fold(start, end, train.getFirst().date(), train.getLast().target(), train.size(), train.stream().map(TrainingProbe.Row::date).toList(),
                    scale.mean, scale.std, TrainingProbe.counts(train), nativeModel,
                    HistoryRun.score(train, predict, scale), HistoryRun.score(validation, predict, scale),
                    selected.isEmpty() ? null : HistoryRun.score(selected, r -> prior, scale).all(),
                    selected.stream().map(TrainingProbe.Row::date).toList(), trainingPredictions, predictions, difference, referenceDifference));
            System.out.println(start + " train=" + train.size() + " accuracy=" + folds.getLast().validation().all().accuracy());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "ohlc-full-history-xgboost-v1"); report.put("gitCommit", args[1]); report.put("createdAt", OffsetDateTime.now());
        report.put("barHash", barHash); report.put("barCount", bars.size()); report.put("inputHash", HistoryRun.inputHash(savedInputs));
        report.put("referenceHash", HistoryRun.fileHash(REFERENCE)); report.put("featureNames", TrainingProbe.NAMES);
        report.put("featureVersion", GoldOhlcFeatures.VERSION); report.put("ruleVersion", GoldForecastRule.RULE_VERSION);
        report.put("parameters", TreeFit.parameters()); report.put("probabilitySumTolerance", .000001); report.put("predictionCheckTolerance", .000002);
        report.put("signalThreshold", .55); report.put("cutoffExclusive", TrainingProbe.LIMIT); report.put("promotionAllowed", false);
        report.put("scope", "固定树模型真实OHLC-only，内部旧验证非盲测，不证明黄金日线修订可得性或未来效果");
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : List.of("TreeFit.java", "TreeChecks.java", "TreeRun.java", "HistorySlice.java", "HistoryChecks.java", "HistoryRun.java", "TrainingProbe.java", "RidgeFit.java", "SoftmaxFit.java"))
            sources.put("docs/research/probes/" + name, TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/probes/" + name))));
        for (String name : List.of("XgboostGoldTrainer.java", "XgboostProperties.java", "GoldFeatureCalculator.java", "GoldOhlcFeatures.java", "DirectionProbabilities.java", "ForecastEvaluator.java")) {
            String file = "backend/src/main/java/com/opspilot/ai/forecast/learning/" + name;
            sources.put(file, TrainingProbe.sha(Files.readAllBytes(Path.of("../" + file))));
        }
        String ruleFile = "backend/src/main/java/com/opspilot/ai/forecast/GoldForecastRule.java";
        sources.put(ruleFile, TrainingProbe.sha(Files.readAllBytes(Path.of("../" + ruleFile))));
        report.put("sourceHashes", sources); report.put("protocolHash", TrainingProbe.sha(Files.readAllBytes(Path.of("../docs/research/2026-10-03-tree-protocol.md"))));
        report.put("bars", bars.stream().map(b -> new Bar(b.priceDate(), b.open().toPlainString(), b.high().toPlainString(), b.low().toPlainString(), b.close().toPlainString())).toList());
        report.put("inputs", savedInputs); report.put("folds", folds);
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report); System.out.println("REPORT " + output);
    }
    static NativeModel archive(Model<Label> model, ObjectMapper json) throws Exception {
        if (!(model instanceof XGBoostModel<?> tree)) throw new IllegalStateException("原生树模型类型错误");
        List<String> features = new ArrayList<>(), labels = new ArrayList<>();
        for (int i = 0; i < model.getFeatureIDMap().size(); i++) features.add(model.getFeatureIDMap().get(i).getName());
        for (int i = 0; i < model.getOutputIDInfo().size(); i++) labels.add(model.getOutputIDInfo().getOutput(i).getLabel());
        if (features.size() != 20 || !new HashSet<>(features).equals(new HashSet<>(TrainingProbe.NAMES)) || labels.size() != 3
                || !new HashSet<>(labels).equals(new HashSet<>(Arrays.asList(TrainingProbe.LABELS)))) throw new IllegalStateException("原生ID映射错误");
        // Tribuo返回的是Booster副本，导出后仅释放副本，不破坏用于预测的模型。
        var copies = tree.getInnerModels();
        try {
            if (copies.size() != 1) throw new IllegalStateException("原生模型数量错误");
            return new NativeModel(features, labels, json.readTree(copies.getFirst().toByteArray("json")));
        } finally { for (var copy : copies) copy.dispose(); }
    }
}
