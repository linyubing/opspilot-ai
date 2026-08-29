package com.opspilot.ai.forecast.backtest.review;

import java.util.List;

/** 保存大模型返回的结构化黄金回测复盘。 */
public record BacktestReviewContent(
        String summary,
        List<String> summaryEvidence,
        List<BacktestErrorPattern> patterns,
        List<BacktestReviewRisk> risks,
        String disclaimer
) {
}
