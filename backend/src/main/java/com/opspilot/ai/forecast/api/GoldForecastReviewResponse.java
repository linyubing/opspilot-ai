package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.review.GeneratedGoldForecastReview;
import com.opspilot.ai.forecast.review.GoldForecastReviewContent;

/** 对外返回结构化预测复盘，不暴露模型原始响应。 */
public record GoldForecastReviewResponse(
        String modelName,
        GoldForecastReviewContent content
) {

    public static GoldForecastReviewResponse from(
            GeneratedGoldForecastReview result
    ) {
        return new GoldForecastReviewResponse(
                result.modelName(),
                result.content()
        );
    }
}
