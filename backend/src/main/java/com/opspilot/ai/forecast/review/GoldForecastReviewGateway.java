package com.opspilot.ai.forecast.review;

/** 定义黄金预测复盘的大模型调用边界。 */
public interface GoldForecastReviewGateway {

    GeneratedGoldForecastReview generate(
            GoldForecastReviewPrompt prompt
    );
}
