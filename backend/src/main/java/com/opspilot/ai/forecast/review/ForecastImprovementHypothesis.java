package com.opspilot.ai.forecast.review;

/** 保存 AI 提出的待验证改进假设及其验证方法。 */
public record ForecastImprovementHypothesis(
        String hypothesis,
        String validationMethod
) {
}
