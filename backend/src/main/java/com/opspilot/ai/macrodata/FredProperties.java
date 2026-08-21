package com.opspilot.ai.macrodata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("opspilot.macro-data.fred")
public record FredProperties(
        URI baseUrl,
        String apiKey,
        String seriesId,
        Duration connectTimeout,
        Duration readTimeout
) {
}
