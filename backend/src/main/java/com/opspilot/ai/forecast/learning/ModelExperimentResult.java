package com.opspilot.ai.forecast.learning;

import java.util.Map;

/** 黄金模型实验运行结果，包含实验记录和各模型的指标。 */
public record ModelExperimentResult(
        ModelExperiment experiment,
        Map<ModelType, ModelExperimentMetric> metrics
) {
    public ModelExperimentResult {
        metrics = Map.copyOf(metrics);
    }

    /** 读取指定模型的持久化指标；找不到时抛出包含模型名称的中文异常。 */
    public ModelExperimentMetric metric(ModelType type) {
        ModelExperimentMetric m = metrics.get(type);
        if (m == null) {
            throw new IllegalArgumentException("未找到模型持久化指标：" + type);
        }
        return m;
    }
}
