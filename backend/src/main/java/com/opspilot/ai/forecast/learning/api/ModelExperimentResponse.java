package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ForecastMetrics;
import com.opspilot.ai.forecast.learning.WalkForwardReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** 对外展示开发验证指标，同时隐藏最终留出集标签。 */
public record ModelExperimentResponse(
        ForecastHorizon horizon,
        LocalDate trainStart,
        LocalDate validationStart,
        LocalDate validationEnd,
        int validationSamples,
        int refitEvery,
        int refitCount,
        MetricResponse majority,
        MetricResponse logistic,
        HoldoutResponse finalHoldout
) {

    public static ModelExperimentResponse from(WalkForwardReport report) {
        return new ModelExperimentResponse(
                report.horizon(),
                report.trainStart(),
                report.validationStart(),
                report.validationEnd(),
                report.validationSamples(),
                report.refitEvery(),
                report.refitCount(),
                MetricResponse.from(report.majority()),
                MetricResponse.from(report.logistic()),
                new HoldoutResponse(
                        report.finalHoldoutSamples(),
                        report.finalHoldoutStart(),
                        report.finalHoldoutEnd()
                )
        );
    }

    /** 保存页面需要的开发验证指标。 */
    public record MetricResponse(
            int sampleCount,
            int coveredCount,
            BigDecimal accuracy,
            BigDecimal balancedAccuracy,
            BigDecimal coverage,
            BigDecimal brierScore,
            Map<ForecastDirection, BigDecimal> recalls,
            boolean promotionReady
    ) {
        private static MetricResponse from(ForecastMetrics metrics) {
            return new MetricResponse(
                    metrics.sampleCount(),
                    metrics.coveredCount(),
                    metrics.accuracy(),
                    metrics.balancedAccuracy(),
                    metrics.coverage(),
                    metrics.brierScore(),
                    metrics.recalls(),
                    metrics.promotionReady()
            );
        }
    }

    /** 只公开最终留出区间的边界，不公开任何评估结果。 */
    public record HoldoutResponse(
            int samples,
            LocalDate start,
            LocalDate end
    ) {
    }
}
