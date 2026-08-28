package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.backtest.BacktestEvaluation;

import java.math.BigDecimal;

/** 返回黄金历史回测的总体、滚动、基线和分方向指标。 */
public record BacktestEvaluationResponse(
        String source,
        int sampleCount,
        BigDecimal accuracy,
        BigDecimal rolling20Accuracy,
        BigDecimal neutralBaselineAccuracy,
        DirectionEvaluation bullish,
        DirectionEvaluation neutral,
        DirectionEvaluation bearish
) {
    public static BacktestEvaluationResponse from(BacktestEvaluation value) {
        return new BacktestEvaluationResponse(
                value.source(), value.sampleCount(), value.accuracy(),
                value.rolling20Accuracy(), value.neutralBaselineAccuracy(),
                value.bullish(), value.neutral(), value.bearish()
        );
    }
}
