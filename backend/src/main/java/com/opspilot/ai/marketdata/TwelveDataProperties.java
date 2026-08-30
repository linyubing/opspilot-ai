package com.opspilot.ai.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 保存 Twelve Data 黄金 OHLC 接口的连接配置。 */
@ConfigurationProperties("opspilot.market-data.twelve-data")
public record TwelveDataProperties(
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
