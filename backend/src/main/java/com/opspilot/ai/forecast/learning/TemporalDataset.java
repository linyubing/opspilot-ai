package com.opspilot.ai.forecast.learning;

import java.util.List;

/** 保存按时间隔离的训练、开发验证和最终留出样本。 */
public record TemporalDataset(
        List<GoldSample> training,
        List<GoldSample> validation,
        List<GoldSample> finalHoldout
) {
    public TemporalDataset {
        training = List.copyOf(training);
        validation = List.copyOf(validation);
        finalHoldout = List.copyOf(finalHoldout);
    }
}
