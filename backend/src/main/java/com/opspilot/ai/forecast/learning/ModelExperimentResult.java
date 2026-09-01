package com.opspilot.ai.forecast.learning;

/** 黄金模型实验运行结果，包含实验记录和两个模型的指标。 */
public record ModelExperimentResult(
        ModelExperiment experiment,
        ModelExperimentMetric majority,
        ModelExperimentMetric logistic
) {
}
