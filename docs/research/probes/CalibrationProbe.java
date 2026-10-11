package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 在冻结训练期内生成严格分离的拟合、校准与验证概率，不调参或写数据库。 */
public class CalibrationProbe {
    record Row(LocalDate date, LocalDate target, ForecastDirection actual,
               DirectionProbabilities model, DirectionProbabilities prior) {}
    record Fold(LocalDate validationStart, LocalDate fitEnd, LocalDate fitTargetEnd, int fitCount,
                Map<ForecastDirection, Integer> fitCounts, List<Row> calibration, List<Row> validation) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("需要冻结输入、新输出和完整提交哈希");
        String frozen = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        var dataset = PriorCompare.JSON.readValue(frozen, GoldDataset.class);
        if (!PriorCompare.HASH.equals(new GoldDatasetFingerprint().hash(dataset)) || dataset.samples().size() != 1268)
            throw new IllegalArgumentException("冻结输入已变化");
        for (var sample : dataset.samples()) if (!sample.targetDate().isBefore(PriorCompare.LIMIT))
            throw new IllegalArgumentException("禁止读取封闭评估期");
        boolean check = args[1].equals("--check");
        Path output = Path.of(args[1]);
        if (!check) {
            if (Files.exists(output)) throw new IllegalArgumentException("禁止覆盖实验");
            Files.writeString(Path.of(args[1] + ".started"), Instant.now().toString(), StandardOpenOption.CREATE_NEW);
        }
        var folds = new ArrayList<Fold>();
        var samples = dataset.samples();
        for (int start = 548; start < samples.size(); start += 240) {
            var outer = PriorCompare.training(samples, start);
            var calibration = outer.subList(outer.size() - 120, outer.size());
            LocalDate first = calibration.getFirst().asOfDate();
            // 校准标签不能进入模型拟合；拟合与校准之间再次清除重叠目标。
            var fit = outer.subList(0, outer.size() - 120).stream().filter(x -> x.targetDate().isBefore(first)).toList();
            if (fit.size() < 400 || !calibration.getLast().targetDate().isBefore(samples.get(start).asOfDate()))
                throw new IllegalArgumentException("校准时间隔离失败");
            if (check) continue;
            var counts = PriorCompare.counts(fit);
            var prior = new DirectionProbabilities((double) counts.get(ForecastDirection.BULLISH) / fit.size(),
                    (double) counts.get(ForecastDirection.NEUTRAL) / fit.size(),
                    (double) counts.get(ForecastDirection.BEARISH) / fit.size());
            var model = new TribuoGoldTrainer(true, false).train(fit, FeatureProfile.ALL_36.featureNames());
            folds.add(new Fold(samples.get(start).asOfDate(), fit.getLast().asOfDate(), fit.getLast().targetDate(),
                    fit.size(), counts, predict(calibration, model, prior),
                    predict(samples.subList(start, start + 240), model, prior)));
        }
        if (check) { System.out.println("FIT_CALIBRATION_VALIDATION_PURGED; NO_TRAINING"); return; }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", "gold-calibration-data-v1"); report.put("gitCommit", args[2]);
        report.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC)); report.put("inputHash", PriorCompare.sha(frozen));
        report.put("datasetHash", PriorCompare.HASH); report.put("holdoutRead", false); report.put("promotionAllowed", false);
        report.put("folds", folds);
        Files.writeString(output, PriorCompare.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report).replace("\r\n", "\n") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static List<Row> predict(List<GoldSample> rows, GoldClassifier model, DirectionProbabilities prior) {
        return rows.stream().map(x -> new Row(x.asOfDate(), x.targetDate(), x.label(), model.predict(x.features()), prior)).toList();
    }
}
