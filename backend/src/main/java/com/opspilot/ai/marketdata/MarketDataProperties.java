package com.opspilot.ai.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("opspilot.market-data.alpha-vantage")
public record MarketDataProperties(
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
