package com.opspilot.ai.forecast.backtest.review;

/** 定义黄金回测复盘的大模型调用边界。 */
public interface BacktestReviewGateway {

    GeneratedBacktestReview generate(BacktestReviewPrompt prompt);
}
