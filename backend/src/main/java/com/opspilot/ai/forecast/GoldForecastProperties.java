package com.opspilot.ai.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 保存黄金方向预测模型的专用配置。 */
@ConfigurationProperties("opspilot.forecast.gold")
public record GoldForecastProperties(String modelName) {
}
