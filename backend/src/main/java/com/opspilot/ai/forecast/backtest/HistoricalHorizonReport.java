package com.opspilot.ai.forecast.backtest;

import java.util.List;

/** 汇总扩大历史样本后的多周期因子诊断结果。 */
public record HistoricalHorizonReport(
        int requestedSamples,
        List<HorizonDiagnostic> horizons
) {
}
