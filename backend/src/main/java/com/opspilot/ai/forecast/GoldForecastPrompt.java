package com.opspilot.ai.forecast;

/** 保存预测提示词版本、内容和 SHA-256 摘要。 */
public record GoldForecastPrompt(String version, String content, String sha256) {
}
