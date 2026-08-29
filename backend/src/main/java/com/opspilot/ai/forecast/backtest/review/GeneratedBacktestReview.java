package com.opspilot.ai.forecast.backtest.review;

/** 封装模型信息、原始响应和结构化回测复盘。 */
public record GeneratedBacktestReview(
        String modelName,
        String rawResponse,
        BacktestReviewContent content
) {
}
