package com.opspilot.ai.forecast.learning;

/** 保存黄金上涨、中性和下跌三个方向的概率。 */
public record DirectionProbabilities(
        double bullish,
        double neutral,
        double bearish
) {
    private static final double TOLERANCE = 0.000001;

    public DirectionProbabilities {
        if (!valid(bullish) || !valid(neutral) || !valid(bearish)) {
            throw new IllegalArgumentException("方向概率必须处于 0 到 1 之间");
        }
        if (Math.abs(bullish + neutral + bearish - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException("方向概率总和必须等于 1");
        }
    }

    private static boolean valid(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
