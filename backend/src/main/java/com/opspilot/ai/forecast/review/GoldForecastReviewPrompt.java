package com.opspilot.ai.forecast.review;

/** 保存黄金预测复盘使用的提示词版本和完整内容。 */
public record GoldForecastReviewPrompt(
        String version,
        String content
) {
}
