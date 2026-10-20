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
        Map<String, Map<String, Integer>> confusionMatrix,
        PairedMetrics paired
) {
    public ModelExperimentMetric {
        if (paired != null && (paired.all().sampleCount() != sampleCount
                || paired.signalDates().size() != coveredCount)) {
            throw new IllegalArgumentException("指标与配对证据的样本数量不一致");
        }
    }
    /** 旧实验缺少逐日配对证据，保留缺失而不是生成虚假的同日期指标。 */
    public ModelExperimentMetric(UUID experimentId, ModelType modelType, int sampleCount,
            int coveredCount, BigDecimal coverage, BigDecimal accuracy,
            BigDecimal balancedAccuracy, BigDecimal brierScore, BigDecimal logLoss,
            Map<String, Object> recalls, Map<String, Map<String, Integer>> confusionMatrix) {
        this(experimentId, modelType, sampleCount, coveredCount, coverage, accuracy,
                balancedAccuracy, brierScore, logLoss, recalls, confusionMatrix, null);
    }
}
