package com.opspilot.ai.forecast.review;

/** 封装模型名称、原始响应和结构化黄金预测复盘。 */
public record GeneratedGoldForecastReview(
        String modelName,
        String rawResponse,
        GoldForecastReviewContent content
) {
}
