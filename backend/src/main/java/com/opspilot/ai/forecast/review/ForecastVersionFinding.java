package com.opspilot.ai.forecast.review;

/** 保存 AI 对某个模型、提示词和规则版本组合的观察。 */
public record ForecastVersionFinding(
        String modelName,
        String promptVersion,
        String ruleVersion,
        String observation
) {
}
