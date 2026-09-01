package com.opspilot.ai.forecast.learning;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** 黄金模型实验评估指标。 */
public record ModelExperimentMetric(
        UUID experimentId,
        ModelType modelType,
        int sampleCount,
        int coveredCount,
        BigDecimal coverage,
        BigDecimal accuracy,
        BigDecimal balancedAccuracy,
        BigDecimal brierScore,
        BigDecimal logLoss,
        Map<String, Object> recalls,
        Map<String, Map<String, Integer>> confusionMatrix
) {
}
