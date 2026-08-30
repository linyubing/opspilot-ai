package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.HistoricalHorizonReport;

import java.math.BigDecimal;
import java.util.List;

/** 向页面返回扩大历史样本后的多周期因子诊断结果。 */
public record HistoricalHorizonResponse(
        int requestedSamples,
        List<HorizonItem> horizons
) {
    public static HistoricalHorizonResponse from(HistoricalHorizonReport report) {
        return new HistoricalHorizonResponse(
                report.requestedSamples(),
                report.horizons().stream().map(horizon -> new HorizonItem(
                        horizon.sessions(),
                        horizon.sampleCount(),
                        horizon.factors().stream().map(factor -> new FactorItem(
                                factor.factor(), factor.coverage(),
                                factor.accuracy(), factor.directionalAccuracy()
                        )).toList()
                )).toList()
        );
    }

    /** 保存一个预测周期的可用样本和因子指标。 */
    public record HorizonItem(
            int sessions,
            int sampleCount,
            List<FactorItem> factors
    ) {
    }

    /** 保存单个因子的覆盖率与准确率。 */
    public record FactorItem(
            String factor,
            BigDecimal coverage,
            BigDecimal accuracy,
            BigDecimal directionalAccuracy
    ) {
    }
}
