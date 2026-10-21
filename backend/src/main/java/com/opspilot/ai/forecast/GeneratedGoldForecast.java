package com.opspilot.ai.forecast;

/** 封装模型名、原始响应和结构化方向预测。 */
public record GeneratedGoldForecast(
        String modelName,
        String rawResponse,
        GoldDirectionForecastContent content,
        String reportedModel
) {
    /** 旧研究调用没有响应身份资料，保持未知。 */
    public GeneratedGoldForecast(String modelName, String rawResponse, GoldDirectionForecastContent content) {
        this(modelName, rawResponse, content, null);
    }
}
