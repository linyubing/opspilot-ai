package com.opspilot.ai.forecast.backtest.review;

import java.util.List;

/** 保存 AI 识别出的复盘风险及其真实样本证据。 */
public record BacktestReviewRisk(
        String description,
        List<String> evidence
) {
}
