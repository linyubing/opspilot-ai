package com.opspilot.ai.forecast.backtest.review;

/** 返回结构化复盘，并标记本次请求是否复用了内存缓存。 */
public record BacktestReviewResult(
        GeneratedBacktestReview review,
        boolean cached
) {
}
