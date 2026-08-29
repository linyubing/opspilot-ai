package com.opspilot.ai.forecast.backtest;

import java.util.List;

/** 保存指定未来交易日周期下的因子诊断结果。 */
public record HorizonDiagnostic(
        int sessions,
        int sampleCount,
        List<FactorDiagnostic> factors
) {
}
