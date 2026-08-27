package com.opspilot.ai.forecast;

/** 封装模型名、原始响应和结构化方向预测。 */
public record GeneratedGoldForecast(
        String modelName,
        String rawResponse,
        GoldDirectionForecastContent content
) {
}
