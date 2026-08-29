package com.opspilot.ai.forecast.backtest;

import java.util.List;
import java.util.UUID;

/** 汇总同一批历史样本在不同预测周期下的因子表现。 */
public record HorizonDiagnosticReport(
        UUID backtestId,
        List<HorizonDiagnostic> horizons
) {
}
