package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.DirectionEvaluation;

import java.math.BigDecimal;

/** 汇总独立历史回测的总体、滚动窗口、基线和分方向表现。 */
public record BacktestEvaluation(
        String source,
        int sampleCount,
        BigDecimal accuracy,
        BigDecimal rolling20Accuracy,
        BigDecimal neutralBaselineAccuracy,
        BigDecimal majorityBaselineAccuracy,
        BigDecimal accuracyLift,
        BigDecimal balancedAccuracy,
        ConfusionMatrix confusionMatrix,
        DirectionEvaluation bullish,
        DirectionEvaluation neutral,
        DirectionEvaluation bearish
) {
}
