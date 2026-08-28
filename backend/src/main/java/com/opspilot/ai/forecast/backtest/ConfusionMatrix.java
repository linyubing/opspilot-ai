package com.opspilot.ai.forecast.backtest;

/** 按真实方向分组，记录模型预测方向的混淆矩阵。 */
public record ConfusionMatrix(
        DirectionCounts actualBullish,
        DirectionCounts actualNeutral,
        DirectionCounts actualBearish
) {

    public static ConfusionMatrix empty() {
        DirectionCounts empty = new DirectionCounts(0, 0, 0);
        return new ConfusionMatrix(empty, empty, empty);
    }
}
