package com.opspilot.ai.forecast.learning;

import java.util.List;
import java.util.Set;

/** 统一黄金分类模型的训练边界。 */
public interface GoldTrainer {
    String name();

    GoldClassifier train(List<GoldSample> samples);

    default GoldClassifier train(List<GoldSample> samples, Set<String> featureNames) {
        return train(samples);
    }
}
