package com.opspilot.ai.forecast.learning;

import java.util.List;

/** 统一黄金分类模型的训练边界。 */
public interface GoldTrainer {
    String name();

    GoldClassifier train(List<GoldSample> samples);
}
