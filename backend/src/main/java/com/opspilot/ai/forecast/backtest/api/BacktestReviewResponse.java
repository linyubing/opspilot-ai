package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.review.BacktestErrorPattern;
import com.opspilot.ai.forecast.backtest.review.GeneratedBacktestReview;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewRisk;

import java.util.List;

/** 返回模型名称和结构化回测复盘，不暴露原始模型响应。 */
public record BacktestReviewResponse(
        String modelName,
        String summary,
        List<String> summaryEvidence,
        List<BacktestErrorPattern> patterns,
        List<BacktestReviewRisk> risks,
        String disclaimer
) {
    public static BacktestReviewResponse from(GeneratedBacktestReview value) {
        return new BacktestReviewResponse(
                value.modelName(),
                value.content().summary(),
                value.content().summaryEvidence(),
                value.content().patterns(),
                value.content().risks(),
                value.content().disclaimer()
        );
    }
}
