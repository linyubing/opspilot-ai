package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.learning.ForecastMetrics;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;

import java.math.BigDecimal;
import java.util.Map;

/** 模型指标响应。 */
public record MetricResponse(
        int sampleCount,
        int coveredCount,
        BigDecimal accuracy,
        BigDecimal balancedAccuracy,
        BigDecimal coverage,
        BigDecimal brierScore,
        BigDecimal logLoss,
        Map<String, Object> recalls,
        boolean promotionReady
) {
    public static MetricResponse from(ForecastMetrics metrics) {
        Map<String, Object> recallMap = new java.util.LinkedHashMap<>();
        for (ForecastDirection direction : ForecastDirection.values()) {
            recallMap.put(direction.name(), metrics.recalls().get(direction));
        }
        return new MetricResponse(
                metrics.sampleCount(),
                metrics.coveredCount(),
                metrics.accuracy(),
                metrics.balancedAccuracy(),
                metrics.coverage(),
                metrics.brierScore(),
                metrics.logLoss(),
                recallMap,
                metrics.promotionReady()
        );
    }

    public static MetricResponse from(ModelExperimentMetric metric) {
        return new MetricResponse(
                metric.sampleCount(),
                metric.coveredCount(),
                metric.accuracy(),
                metric.balancedAccuracy(),
                metric.coverage(),
                metric.brierScore(),
                metric.logLoss(),
                metric.recalls(),
                false
        );
    }
}
