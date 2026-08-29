package com.opspilot.ai.forecast.backtest.review;

import java.util.Set;

/** 保存黄金回测复盘提示词及其版本。 */
public record BacktestReviewPrompt(
        String version,
        String content,
        Set<String> evidenceIds
) {
}
