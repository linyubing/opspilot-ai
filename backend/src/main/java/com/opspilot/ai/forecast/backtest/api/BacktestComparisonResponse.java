package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.BacktestComparison;

import java.math.BigDecimal;
import java.util.UUID;

/** 向前端返回基准回测与候选回测的核心指标差异。 */
public record BacktestComparisonResponse(
        UUID baselineId,
        UUID candidateId,
        int sampleCount,
        BigDecimal baselineAccuracy,
        BigDecimal candidateAccuracy,
        BigDecimal accuracyChange,
        BigDecimal baselineBalancedAccuracy,
        BigDecimal candidateBalancedAccuracy,
        BigDecimal balancedAccuracyChange
) {
    public static BacktestComparisonResponse from(BacktestComparison value) {
        return new BacktestComparisonResponse(
                value.baselineId(),
                value.candidateId(),
                value.sampleCount(),
                value.baseline().accuracy(),
                value.candidate().accuracy(),
                value.accuracyChange(),
                value.baseline().balancedAccuracy(),
                value.candidate().balancedAccuracy(),
                value.balancedAccuracyChange()
        );
    }
}
