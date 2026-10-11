package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.opspilot.ai.forecast.ForecastDirection;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** 独立复算校准最优性及真实验证评分，不重训或改写归档。 */
public class CheckShrink {
    static final String[] KEYS = {"bullish", "neutral", "bearish"};
    static final String[] LABELS = {"BULLISH", "NEUTRAL", "BEARISH"};
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("需要冻结输入、原始概率、收缩结果");
        var samples = PriorCompare.JSON.readValue(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8), GoldDataset.class).samples();
        String raw = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        var data = PriorCompare.JSON.readTree(raw);
        var result = PriorCompare.JSON.readTree(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8));
        check(PriorCompare.sha(raw).equals(result.path("rawInputHash").asText()), "RAW_HASH");
        check(!result.path("holdoutRead").asBoolean() && !result.path("promotionAllowed").asBoolean(), "SCOPE");
        check(result.path("folds").size() == 3, "FOLDS");
        int hits = 0;
        for (int i = 0; i < 3; i++) {
            var fold = result.path("folds").get(i);
            var source = data.path("folds").get(i);
            var outer = PriorCompare.training(samples, 548 + 240 * i);
            var cal = outer.subList(outer.size() - 120, outer.size());
            var fit = outer.subList(0, outer.size() - 120).stream()
                    .filter(x -> x.targetDate().isBefore(cal.getFirst().asOfDate())).toList();
            check(fit.size() == source.path("fitCount").asInt(), "FIT_COUNT");
            double weight = fold.path("weight").asDouble();
            check(Double.isFinite(weight) && weight >= 0 && weight <= 1, "WEIGHT");
            double derivative = 0, loss = 0;
            for (int j = 0; j < 120; j++) {
                var row = source.path("calibration").get(j);
                var sample = cal.get(j);
                check(row.path("date").asText().equals(sample.asOfDate().toString())
                        && row.path("target").asText().equals(sample.targetDate().toString())
                        && row.path("actual").asText().equals(sample.label().name()), "CALIBRATION_ROW");
                String key = KEYS[Arrays.asList(LABELS).indexOf(row.path("actual").asText())];
                double model = row.path("model").path(key).asDouble(), prior = row.path("prior").path(key).asDouble();
                double mixed = (1 - weight) * model + weight * prior;
                check(mixed > 0, "POSITIVE_ACTUAL");
                derivative += (model - prior) / mixed / 120;
                loss -= Math.log(mixed) / 120;
            }
            check(weight == 0 ? derivative >= -1e-12 : weight == 1 ? derivative <= 1e-12 : Math.abs(derivative) < 1e-10, "CONVEX_OPTIMALITY");
            near(loss, fold.path("calibrationLoss").asDouble(), 1e-12);
            near(derivative, fold.path("calibrationSlope").asDouble(), 1e-12);
            check(fold.path("rows").size() == 240, "VALIDATION_SIZE");
            for (String field : List.of("model", "prior", "shrunk")) {
                var predictions = new ArrayList<SettledPrediction>();
                for (int j = 0; j < 240; j++) {
                    var row = fold.path("rows").get(j);
                    var original = source.path("validation").get(j);
                    var sample = samples.get(548 + 240 * i + j);
                    check(row.path("date").asText().equals(sample.asOfDate().toString())
                            && row.path("target").asText().equals(sample.targetDate().toString())
                            && row.path("actual").asText().equals(sample.label().name()), "VALIDATION_ROW");
                    check(row.path("model").equals(original.path("model")) && row.path("prior").equals(original.path("prior")), "RAW_PROBABILITIES");
                    for (String key : KEYS) near(row.path("shrunk").path(key).asDouble(),
                            (1 - weight) * original.path("model").path(key).asDouble()
                                    + weight * original.path("prior").path(key).asDouble(), 1e-12);
                    var p = PriorCompare.JSON.treeToValue(row.path(field), DirectionProbabilities.class);
                    predictions.add(PriorCompare.settle(sample, p, false));
                }
                var metrics = new ForecastEvaluator().evaluate(predictions);
                var score = fold.path(field);
                near(metrics.accuracy().doubleValue(), score.path("accuracy").asDouble(), .000051);
                near(metrics.brierScore().doubleValue(), score.path("brier").asDouble(), .000051);
                near(metrics.logLoss().doubleValue(), score.path("logLoss").asDouble(), .000051);
                near(metrics.balancedAccuracy().doubleValue(), score.path("balancedAccuracy").asDouble(), .00011);
                check(PriorCompare.JSON.valueToTree(metrics.confusionMatrix()).equals(score.path("matrix")), "MATRIX");
                if (field.equals("shrunk")) {
                    hits += score.path("hits").asInt();
                    long signals = predictions.stream().filter(x -> new ConfidencePolicy(.55)
                            .apply(x.probabilities()).status() == SignalStatus.PREDICTED).count();
                    near((double) signals / predictions.size(), fold.path("coverage").asDouble(), 1e-12);
                    check(signals == fold.path("signal").path("sampleCount").asLong(), "SIGNAL_COUNT");
                    if (signals == 0) check(fold.path("signal").path("accuracy").isNull()
                            && fold.path("priorOnSignals").path("accuracy").isNull(), "NO_SIGNAL_SCORE");
                }
            }
        }
        System.out.println("INDEPENDENT_CONVEX_OPTIMALITY_AND_SCORES_PASSED; SHRUNK_HITS=" + hits + "/720; NO_PROMOTION");
    }
    static void near(double a, double b, double tolerance) { check(Math.abs(a - b) <= tolerance, "NUMERIC_MISMATCH"); }
    static void check(boolean accepted, String reason) { if (!accepted) throw new IllegalArgumentException(reason); }
}
