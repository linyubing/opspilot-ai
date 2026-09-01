package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 计算覆盖率、方向准确率、平衡准确率和概率误差。 */
@Component
public class ForecastEvaluator {

    private static final BigDecimal EPSILON = new BigDecimal("1e-15");
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    public ForecastMetrics evaluate(List<SettledPrediction> predictions) {
        if (predictions == null || predictions.isEmpty()) {
            throw new IllegalArgumentException("已结算预测不能为空");
        }
        Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix = matrix();
        int covered = 0;
        int correct = 0;
        double brier = 0;
        double logLossSum = 0;
        for (SettledPrediction item : predictions) {
            brier += brier(item);
            logLossSum += logLoss(item);
            if (item.prediction().status() == SignalStatus.NO_SIGNAL) {
                continue;
            }
            covered++;
            ForecastDirection predicted = item.prediction().direction();
            matrix.get(item.actual()).compute(predicted, (key, value) -> value + 1);
            if (predicted == item.actual()) {
                correct++;
            }
        }

        Map<ForecastDirection, BigDecimal> recalls = new EnumMap<>(ForecastDirection.class);
        BigDecimal recallSum = BigDecimal.ZERO;
        boolean complete = true;
        for (ForecastDirection direction : ForecastDirection.values()) {
            int total = matrix.get(direction).values().stream().mapToInt(Integer::intValue).sum();
            BigDecimal recall = total == 0 ? null
                    : ratio(matrix.get(direction).get(direction), total);
            recalls.put(direction, recall);
            if (recall == null) {
                complete = false;
            } else {
                recallSum = recallSum.add(recall);
            }
        }

        BigDecimal balanced = complete
                ? recallSum.divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP)
                : null;
        return new ForecastMetrics(
                predictions.size(),
                covered,
                ratio(covered, predictions.size()),
                covered == 0 ? BigDecimal.ZERO.setScale(4) : ratio(correct, covered),
                balanced,
                BigDecimal.valueOf(brier / predictions.size()).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(logLossSum / predictions.size()).setScale(4, RoundingMode.HALF_UP),
                recalls,
                matrix,
                complete && covered > 0
        );
    }

    private Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix() {
        Map<ForecastDirection, Map<ForecastDirection, Integer>> result =
                new EnumMap<>(ForecastDirection.class);
        for (ForecastDirection actual : ForecastDirection.values()) {
            Map<ForecastDirection, Integer> row = new EnumMap<>(ForecastDirection.class);
            for (ForecastDirection predicted : ForecastDirection.values()) {
                row.put(predicted, 0);
            }
            result.put(actual, row);
        }
        return result;
    }

    private double brier(SettledPrediction item) {
        DirectionProbabilities p = item.probabilities();
        return square(p.bullish() - target(item.actual(), ForecastDirection.BULLISH))
                + square(p.neutral() - target(item.actual(), ForecastDirection.NEUTRAL))
                + square(p.bearish() - target(item.actual(), ForecastDirection.BEARISH));
    }

    private double logLoss(SettledPrediction item) {
        DirectionProbabilities p = item.probabilities();
        double pActual = probabilityForActual(item.actual(), p);
        return -Math.log(Math.max(pActual, EPSILON.doubleValue()));
    }

    private double probabilityForActual(ForecastDirection actual, DirectionProbabilities p) {
        return switch (actual) {
            case BULLISH -> p.bullish();
            case NEUTRAL -> p.neutral();
            case BEARISH -> p.bearish();
        };
    }

    private int target(ForecastDirection actual, ForecastDirection direction) {
        return actual == direction ? 1 : 0;
    }

    private double square(double value) {
        return value * value;
    }

    private BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
