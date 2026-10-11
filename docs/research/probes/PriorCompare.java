package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 冻结真实训练期内对比类别频率与现有模型，不访问数据库或封闭评估期。 */
public class PriorCompare {
    static final String HASH = "fbca9db1f618ee5f7b144c6f32ab155352f3a5dd4d8de7eece854602409fa68d";
    static final LocalDate LIMIT = LocalDate.parse("2024-11-11");
    static final int SIZE = 240;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    record Row(LocalDate date, LocalDate target, ForecastDirection actual,
               DirectionProbabilities prior, DirectionProbabilities logistic) {}
    record Fold(LocalDate start, LocalDate end, LocalDate lastTrainingTarget, int trainingCount,
                Map<ForecastDirection, Integer> trainingCounts,
                ForecastMetrics priorAll, ForecastMetrics majorityAll, ForecastMetrics logisticAll,
                ForecastMetrics logisticSignals, ForecastMetrics priorOnLogisticSignals,
                int logisticOnlyHits, int priorOnlyHits, List<Row> rows) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要冻结输入、新输出路径和完整提交哈希");
        String frozen = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        GoldDataset dataset = JSON.readValue(frozen, GoldDataset.class);
        if (!HASH.equals(new GoldDatasetFingerprint().hash(dataset)) || dataset.samples().size() != 1268)
            throw new IllegalArgumentException("冻结人群已变化，禁止配对");
        var samples = dataset.samples();
        for (int i = 0; i < samples.size(); i++) {
            var sample = samples.get(i);
            if (!sample.targetDate().isBefore(LIMIT) || sample.horizon() != ForecastHorizon.NEXT_DAY
                    || (i > 0 && !sample.asOfDate().isAfter(samples.get(i - 1).asOfDate())))
                throw new IllegalArgumentException("冻结日期隔离失败");
        }
        if (args[1].equals("--check")) {
            for (int start = samples.size() - 3 * SIZE; start < samples.size(); start += SIZE)
                training(samples, start);
            System.out.println("INPUT_HASH_MATCH; THREE_FOLDS_PURGED; NO_TRAINING");
            return;
        }
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("禁止覆盖结果");
        // 原子开始标记防止崩溃后换参数重跑同一实验。
        Files.writeString(Path.of(args[1] + ".started"), Instant.now().toString(), StandardOpenOption.CREATE_NEW);
        var folds = new ArrayList<Fold>();
        var evaluator = new ForecastEvaluator();
        for (int start = samples.size() - 3 * SIZE; start < samples.size(); start += SIZE) {
            var train = training(samples, start);
            var counts = counts(train);
            var prior = new DirectionProbabilities((double) counts.get(ForecastDirection.BULLISH) / train.size(),
                    (double) counts.get(ForecastDirection.NEUTRAL) / train.size(),
                    (double) counts.get(ForecastDirection.BEARISH) / train.size());
            var majority = new MajorityGoldTrainer().train(train, FeatureProfile.ALL_36.featureNames());
            var logistic = new TribuoGoldTrainer(true, false).train(train, FeatureProfile.ALL_36.featureNames());
            List<SettledPrediction> pAll = new ArrayList<>(), mAll = new ArrayList<>(), lAll = new ArrayList<>(),
                    signals = new ArrayList<>(), pSelected = new ArrayList<>();
            var rows = new ArrayList<Row>();
            int lOnly = 0, pOnly = 0;
            for (var sample : samples.subList(start, start + SIZE)) {
                var p = settle(sample, prior, false);
                var l = settle(sample, logistic.predict(sample.features()), false);
                var signal = settle(sample, l.probabilities(), true);
                pAll.add(p); lAll.add(l); signals.add(signal);
                mAll.add(settle(sample, majority.predict(sample.features()), false));
                if (signal.prediction().status() == SignalStatus.PREDICTED) pSelected.add(p);
                boolean lh = l.prediction().direction() == sample.label(), ph = p.prediction().direction() == sample.label();
                if (lh && !ph) lOnly++;
                if (ph && !lh) pOnly++;
                rows.add(new Row(sample.asOfDate(), sample.targetDate(), sample.label(), prior, l.probabilities()));
            }
            var fold = new Fold(rows.getFirst().date(), rows.getLast().date(), train.getLast().targetDate(),
                    train.size(), counts, evaluator.evaluate(pAll), evaluator.evaluate(mAll), evaluator.evaluate(lAll),
                    evaluator.evaluate(signals), pSelected.isEmpty() ? null : evaluator.evaluate(pSelected), lOnly, pOnly, rows);
            folds.add(fold);
            System.out.println("FOLD " + fold.start() + "; priorAcc=" + fold.priorAll().accuracy()
                    + "; logisticAcc=" + fold.logisticAll().accuracy());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "gold-prior-compare-v1"); result.put("gitCommit", args[2]);
        result.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC)); result.put("inputHash", sha(frozen));
        result.put("datasetHash", HASH); result.put("sampleCount", samples.size());
        result.put("cutoffExclusive", LIMIT); result.put("holdoutRead", false); result.put("promotionAllowed", false);
        result.put("trainer", "Tribuo LogisticRegressionTrainer default five epochs, seed 12345, fold scaling, ALL_36, no weights");
        result.put("refit", "one fixed model per 240-sample internal block; not product 20-day walk-forward");
        result.put("signalThreshold", 0.55); result.put("folds", folds);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static List<GoldSample> training(List<GoldSample> samples, int start) {
        LocalDate first = samples.get(start).asOfDate();
        var train = samples.subList(0, start).stream().filter(x -> x.targetDate().isBefore(first)).toList();
        if (train.size() < 500) throw new IllegalArgumentException("训练人群不足");
        return train;
    }
    static Map<ForecastDirection, Integer> counts(List<GoldSample> train) {
        Map<ForecastDirection, Integer> counts = new EnumMap<>(ForecastDirection.class);
        for (var direction : ForecastDirection.values()) counts.put(direction, 0);
        train.forEach(x -> counts.merge(x.label(), 1, Integer::sum));
        return counts;
    }
    static SettledPrediction settle(GoldSample sample, DirectionProbabilities p, boolean signal) {
        // 全人群固定 argmax 并列顺序；信号指标另用既有 0.55 门槛，不根据实际标签选方向。
        var d = p.bullish() >= p.neutral() && p.bullish() >= p.bearish() ? ForecastDirection.BULLISH
                : p.neutral() >= p.bearish() ? ForecastDirection.NEUTRAL : ForecastDirection.BEARISH;
        var prediction = signal ? new ConfidencePolicy(0.55).apply(p)
                : new GoldPrediction(SignalStatus.PREDICTED, d, Math.max(p.bullish(), Math.max(p.neutral(), p.bearish())));
        return new SettledPrediction(sample.asOfDate(), p, prediction, sample.label());
    }
    static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
