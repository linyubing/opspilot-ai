package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.util.List;

/** 汇总黄金方向预测的总体、分方向、滚动窗口和版本表现。 */
public record GoldForecastEvaluation(
        int totalCount,
        int pendingCount,
        int resolvedCount,
        BigDecimal overallAccuracy,
        DirectionEvaluation bullish,
        DirectionEvaluation neutral,
        DirectionEvaluation bearish,
        BigDecimal rolling20Accuracy,
        BigDecimal neutralBaselineAccuracy,
        List<ForecastVersionEvaluation> versions
) {
}
