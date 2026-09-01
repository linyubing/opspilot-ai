package com.opspilot.ai.forecast.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** XGBoost 模型配置属性。训练器和实验记录必须读取同一份配置。 */
@ConfigurationProperties(prefix = "opspilot.forecast.gold.xgboost")
public record XgboostProperties(
        int numTrees,
        double eta,
        double gamma,
        int maxDepth,
        int minChildWeight,
        double subsample,
        double featureSubsample,
        double lambda,
        double alpha,
        int nThread,
        long seed
) {
    public XgboostProperties {
        if (numTrees <= 0) {
            throw new IllegalArgumentException("numTrees 必须大于 0");
        }
        if (eta <= 0 || eta > 1) {
            throw new IllegalArgumentException("eta 必须在 (0, 1] 之间");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth 必须大于 0");
        }
        if (minChildWeight < 0) {
            throw new IllegalArgumentException("minChildWeight 不能为负数");
        }
        if (subsample <= 0 || subsample > 1) {
            throw new IllegalArgumentException("subsample 必须在 (0, 1] 之间");
        }
        if (featureSubsample <= 0 || featureSubsample > 1) {
            throw new IllegalArgumentException("featureSubsample 必须在 (0, 1] 之间");
        }
        if (lambda < 0) {
            throw new IllegalArgumentException("lambda 不能为负数");
        }
        if (alpha < 0) {
            throw new IllegalArgumentException("alpha 不能为负数");
        }
        if (nThread <= 0) {
            throw new IllegalArgumentException("nThread 必须大于 0");
        }
    }
}
