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
        List<ForecastVersionEvaluation> versions,
        GoldForecastTimingEvaluation timing,
        GoldTargetEvaluation independent,
        GoldSignalEvaluation signals
) {
    /** 旧调用没有逐条信号证据，不补造覆盖率。 */
    public GoldForecastEvaluation(int totalCount, int pendingCount, int resolvedCount,
            BigDecimal overallAccuracy, DirectionEvaluation bullish, DirectionEvaluation neutral,
            DirectionEvaluation bearish, BigDecimal rolling20Accuracy, BigDecimal neutralBaselineAccuracy,
            List<ForecastVersionEvaluation> versions, GoldForecastTimingEvaluation timing, GoldTargetEvaluation independent) {
        this(totalCount, pendingCount, resolvedCount, overallAccuracy, bullish, neutral, bearish,
                rolling20Accuracy, neutralBaselineAccuracy, versions, timing, independent, null);
    }
    public GoldForecastEvaluation(int totalCount, int pendingCount, int resolvedCount,
            BigDecimal overallAccuracy, DirectionEvaluation bullish, DirectionEvaluation neutral,
            DirectionEvaluation bearish, BigDecimal rolling20Accuracy, BigDecimal neutralBaselineAccuracy,
            List<ForecastVersionEvaluation> versions, GoldForecastTimingEvaluation timing) {
        this(totalCount, pendingCount, resolvedCount, overallAccuracy, bullish, neutral, bearish,
                rolling20Accuracy, neutralBaselineAccuracy, versions, timing, GoldTargetEvaluation.from(List.of()));
    }
    public GoldForecastEvaluation(int totalCount, int pendingCount, int resolvedCount,
            BigDecimal overallAccuracy, DirectionEvaluation bullish, DirectionEvaluation neutral,
            DirectionEvaluation bearish, BigDecimal rolling20Accuracy, BigDecimal neutralBaselineAccuracy,
            List<ForecastVersionEvaluation> versions) {
        this(totalCount, pendingCount, resolvedCount, overallAccuracy, bullish, neutral, bearish,
                rolling20Accuracy, neutralBaselineAccuracy, versions,
                GoldForecastTimingEvaluation.legacy(resolvedCount, overallAccuracy));
    }
}
