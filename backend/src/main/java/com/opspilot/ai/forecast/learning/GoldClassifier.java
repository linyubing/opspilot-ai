package com.opspilot.ai.forecast.learning;

/** 统一黄金分类模型的概率预测边界。 */
@FunctionalInterface
public interface GoldClassifier {
    DirectionProbabilities predict(GoldFeatures features);
}
