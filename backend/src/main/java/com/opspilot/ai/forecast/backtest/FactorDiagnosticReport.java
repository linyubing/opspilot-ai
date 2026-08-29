package com.opspilot.ai.forecast.backtest;

import java.util.List;
import java.util.UUID;

/** 汇总一次已结算回测中各输入因子的独立方向表现。 */
public record FactorDiagnosticReport(
        UUID backtestId,
        int sampleCount,
        List<FactorDiagnostic> factors
) {
}
