package com.opspilot.ai.forecast.backtest;

import java.math.BigDecimal;

/** 保存一个波动区间内明确方向信号的命中表现。 */
public record VolatilityDiagnostic(
        VolatilityRegime regime,
        int sampleCount,
        int signalCount,
        int hitCount,
        BigDecimal accuracy
) {
}
