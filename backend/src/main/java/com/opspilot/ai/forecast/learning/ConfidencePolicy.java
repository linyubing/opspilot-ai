package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;

/** 把模型概率转换为正式方向或证据不足状态。 */
public class ConfidencePolicy {

    private static final double EPSILON = 0.000001;
    private final double threshold;

    public ConfidencePolicy(double threshold) {
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("置信度阈值必须处于 0 到 1 之间");
        }
        this.threshold = threshold;
    }

    public GoldPrediction apply(DirectionProbabilities probabilities) {
        double max = Math.max(
                probabilities.bullish(),
                Math.max(probabilities.neutral(), probabilities.bearish())
        );
        int winners = 0;
        winners += near(probabilities.bullish(), max) ? 1 : 0;
        winners += near(probabilities.neutral(), max) ? 1 : 0;
        winners += near(probabilities.bearish(), max) ? 1 : 0;
        if (max < threshold || winners != 1) {
            return new GoldPrediction(SignalStatus.NO_SIGNAL, null, max);
        }
        ForecastDirection direction = probabilities.bullish() == max
                ? ForecastDirection.BULLISH
                : probabilities.neutral() == max
                ? ForecastDirection.NEUTRAL
                : ForecastDirection.BEARISH;
        return new GoldPrediction(SignalStatus.PREDICTED, direction, max);
    }

    private boolean near(double left, double right) {
        return Math.abs(left - right) <= EPSILON;
    }
}
