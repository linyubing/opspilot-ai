package com.opspilot.ai.forecast;

import java.math.BigDecimal;

/** 保存一个模型、提示词和规则版本组合的预测表现。 */
public record ForecastVersionEvaluation(
        String modelName,
        String promptVersion,
        String forecastRuleVersion,
        int sampleCount,
        int hitCount,
        BigDecimal accuracy,
        GoldSignalEvaluation signals,
        String reportedModel,
        String runKey
) {
    public ForecastVersionEvaluation(String modelName, String promptVersion, String forecastRuleVersion,
            int sampleCount, int hitCount, BigDecimal accuracy, GoldSignalEvaluation signals, String reportedModel) {
        this(modelName,promptVersion,forecastRuleVersion,sampleCount,hitCount,accuracy,signals,reportedModel,null);
    }
    public ForecastVersionEvaluation(String modelName, String promptVersion, String forecastRuleVersion,
            int sampleCount, int hitCount, BigDecimal accuracy, GoldSignalEvaluation signals) {
        this(modelName, promptVersion, forecastRuleVersion, sampleCount, hitCount, accuracy, signals, null);
    }
    /** 兼容旧汇总调用；没有逐条信号证据时不补造覆盖率。 */
    public ForecastVersionEvaluation(String modelName, String promptVersion, String forecastRuleVersion,
            int sampleCount, int hitCount, BigDecimal accuracy) {
        this(modelName, promptVersion, forecastRuleVersion, sampleCount, hitCount, accuracy, null);
    }
}
