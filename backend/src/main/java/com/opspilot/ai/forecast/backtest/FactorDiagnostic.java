package com.opspilot.ai.forecast.backtest;

import java.math.BigDecimal;

/** 保存单个研究因子对下一交易日方向的独立诊断指标。 */
public record FactorDiagnostic(
        String factor,
        int sampleCount,
        int directionalCount,
        BigDecimal coverage,
        int hitCount,
        BigDecimal accuracy,
        int directionalHitCount,
        BigDecimal directionalAccuracy,
        DirectionCounts signals
) {
}
