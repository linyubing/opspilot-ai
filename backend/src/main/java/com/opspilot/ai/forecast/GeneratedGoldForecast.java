package com.opspilot.ai.forecast;

/** 封装模型名、原始响应和结构化方向预测。 */
public record GeneratedGoldForecast(
        String modelName,
        String rawResponse,
        GoldDirectionForecastContent content,
        String reportedModel,
        GoldModelRun modelRun
) {
    /** 旧网关没有逐次运行依据，保持未知。 */
    public GeneratedGoldForecast(String modelName, String rawResponse, GoldDirectionForecastContent content, String reportedModel) {
        this(modelName, rawResponse, content, reportedModel, null);
    }
    /** 旧研究调用没有响应身份资料，保持未知。 */
    public GeneratedGoldForecast(String modelName, String rawResponse, GoldDirectionForecastContent content) {
        this(modelName, rawResponse, content, null);
    }
}
