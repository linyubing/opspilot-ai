package com.opspilot.ai.forecast;

import java.math.BigDecimal;

/** 保存一个模型、提示词和规则版本组合的预测表现。 */
public record ForecastVersionEvaluation(
        String modelName,
        String promptVersion,
        String forecastRuleVersion,
        int sampleCount,
        int hitCount,
        BigDecimal accuracy
) {
}
