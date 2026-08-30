package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.backtest.BacktestConclusion;
import com.opspilot.ai.forecast.backtest.BacktestEvaluation;
import com.opspilot.ai.forecast.backtest.BacktestPriceBasis;
import com.opspilot.ai.forecast.backtest.BacktestSampleSet;
import com.opspilot.ai.forecast.backtest.ConfusionMatrix;

import java.math.BigDecimal;

/** 返回黄金历史回测的总体、滚动、基线和分方向指标。 */
public record BacktestEvaluationResponse(
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
        BacktestConclusion conclusion,
        DirectionEvaluation bullish,
        DirectionEvaluation neutral,
        DirectionEvaluation bearish
) {
    public static BacktestEvaluationResponse from(BacktestEvaluation value) {
        return new BacktestEvaluationResponse(
                value.source(), value.priceBasis(), value.sampleSet(),
                value.sampleCount(), value.accuracy(),
                value.rolling20Accuracy(), value.neutralBaselineAccuracy(),
                value.majorityBaselineAccuracy(), value.accuracyLift(),
                value.balancedAccuracy(), value.beatsBaseline(),
                value.promotionReady(), value.confusionMatrix(),
                BacktestConclusion.from(value),
                value.bullish(), value.neutral(), value.bearish()
        );
    }
}
