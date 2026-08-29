package com.opspilot.ai.forecast.backtest;

import java.math.BigDecimal;
import java.util.UUID;

/** 保存基准提示词与候选提示词在相同历史样本上的指标差异。 */
public record BacktestComparison(
        UUID baselineId,
        UUID candidateId,
        int sampleCount,
        BacktestEvaluation baseline,
        BacktestEvaluation candidate,
        BigDecimal accuracyChange,
        BigDecimal balancedAccuracyChange
) {
}
