package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.DirectionEvaluation;

import java.math.BigDecimal;

/** 汇总独立历史回测的总体、滚动窗口、基线和分方向表现。 */
public record BacktestEvaluation(
        String source,
        BacktestPriceBasis priceBasis,
        BacktestSampleSet sampleSet,
        int sampleCount,
        BigDecimal accuracy,
        BigDecimal rolling20Accuracy,
        BigDecimal neutralBaselineAccuracy,
        BigDecimal majorityBaselineAccuracy,
        BigDecimal accuracyLift,
        BigDecimal balancedAccuracy,
        boolean beatsBaseline,
        boolean promotionReady,
        ConfusionMatrix confusionMatrix,
        DirectionEvaluation bullish,
        DirectionEvaluation neutral,
        DirectionEvaluation bearish,
        int signalCount,
        BigDecimal coverage,
        boolean probabilityMetricsAvailable,
        BigDecimal brierScore,
        BigDecimal logLoss
) {
    /** 大模型回测专用构造器：无概率输出，概率指标不可用。 */
    public BacktestEvaluation(
            String source,
            BacktestPriceBasis priceBasis,
            BacktestSampleSet sampleSet,
            int sampleCount,
            BigDecimal accuracy,
            BigDecimal rolling20Accuracy,
            BigDecimal neutralBaselineAccuracy,
            BigDecimal majorityBaselineAccuracy,
            BigDecimal accuracyLift,
            BigDecimal balancedAccuracy,
            boolean beatsBaseline,
            boolean promotionReady,
            ConfusionMatrix confusionMatrix,
            DirectionEvaluation bullish,
            DirectionEvaluation neutral,
            DirectionEvaluation bearish
    ) {
        this(
                source, priceBasis, sampleSet, sampleCount, accuracy,
                rolling20Accuracy, neutralBaselineAccuracy,
                majorityBaselineAccuracy, accuracyLift, balancedAccuracy,
                beatsBaseline, promotionReady, confusionMatrix,
                bullish, neutral, bearish,
                sampleCount, BigDecimal.ONE, false, null, null
        );
    }

    /** 兼容原有指标构造，仅供不关心准入状态的旧测试和比较逻辑使用。 */
    public BacktestEvaluation(
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
        this(
                source, BacktestPriceBasis.LEGACY_REFERENCE,
                BacktestSampleSet.DEFAULT, sampleCount, accuracy,
                rolling20Accuracy, neutralBaselineAccuracy,
                majorityBaselineAccuracy, accuracyLift, balancedAccuracy,
                false, false, confusionMatrix, bullish, neutral, bearish,
                sampleCount, BigDecimal.ONE, false, null, null
        );
    }
}
