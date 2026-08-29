package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.HorizonDiagnosticReport;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 向页面返回不同未来交易日周期下的因子准确率。 */
public record HorizonDiagnosticResponse(
        UUID backtestId,
        List<HorizonItemResponse> horizons
) {
    public static HorizonDiagnosticResponse from(HorizonDiagnosticReport report) {
        return new HorizonDiagnosticResponse(
                report.backtestId(),
                report.horizons().stream().map(horizon ->
                        new HorizonItemResponse(
                                horizon.sessions(),
                                horizon.sampleCount(),
                                horizon.factors().stream().map(factor ->
                                        new HorizonFactorResponse(
                                                factor.factor(),
                                                factor.coverage(),
                                                factor.accuracy(),
                                                factor.directionalAccuracy()
                                        )
                                ).toList()
                        )
                ).toList()
        );
    }

    /** 保存一个预测周期的可用样本和各因子结果。 */
    public record HorizonItemResponse(
            int sessions,
            int sampleCount,
            List<HorizonFactorResponse> factors
    ) {
    }

    /** 保存一个因子在指定周期下的核心指标。 */
    public record HorizonFactorResponse(
            String factor,
            BigDecimal coverage,
            BigDecimal accuracy,
            BigDecimal directionalAccuracy
    ) {
    }
}
