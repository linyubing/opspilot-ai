package com.opspilot.ai.macrodata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 保存 FRED 数据源连接参数和各领域序列编号。 */
@ConfigurationProperties("opspilot.macro-data.fred")
public record FredProperties(
        URI baseUrl,
        String apiKey,
        String seriesId,
        String dollarIndexSeriesId,
        Duration connectTimeout,
        Duration readTimeout
) {
}
