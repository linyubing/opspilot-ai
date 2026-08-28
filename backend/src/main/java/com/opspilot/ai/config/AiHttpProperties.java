package com.opspilot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 保存大模型同步 HTTP 调用的连接和读取超时。 */
@ConfigurationProperties("opspilot.ai.http")
public record AiHttpProperties(
        Duration connectTimeout,
        Duration readTimeout
) {
}
