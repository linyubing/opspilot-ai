package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.DirectionCounts;
import com.opspilot.ai.forecast.backtest.FactorDiagnostic;
import com.opspilot.ai.forecast.backtest.FactorDiagnosticReport;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 向页面返回各研究因子的独立方向诊断结果。 */
public record FactorDiagnosticResponse(
        UUID backtestId,
        int sampleCount,
        List<FactorItemResponse> factors
) {
    public static FactorDiagnosticResponse from(FactorDiagnosticReport report) {
        return new FactorDiagnosticResponse(
                report.backtestId(),
                report.sampleCount(),
                report.factors().stream().map(FactorItemResponse::from).toList()
        );
    }

    /** 返回一个因子的覆盖率、命中率和信号分布。 */
    public record FactorItemResponse(
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
        private static FactorItemResponse from(FactorDiagnostic item) {
            return new FactorItemResponse(
                    item.factor(), item.sampleCount(), item.directionalCount(),
                    item.coverage(), item.hitCount(), item.accuracy(),
                    item.directionalHitCount(), item.directionalAccuracy(),
                    item.signals()
            );
        }
    }
}
