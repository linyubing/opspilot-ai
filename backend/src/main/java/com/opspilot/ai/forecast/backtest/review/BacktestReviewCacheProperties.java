package com.opspilot.ai.forecast.backtest.review;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 保存黄金回测 AI 复盘的本地缓存容量和访问过期时间。 */
@Validated
@ConfigurationProperties("opspilot.forecast.gold.backtest-review-cache")
public record BacktestReviewCacheProperties(
        @Min(1) long maxSize,
        @NotNull Duration ttl
) {
    /** 缓存有效期必须大于零，避免启动后立即失效或配置异常。 */
    @AssertTrue(message = "ttl 必须大于零")
    public boolean hasValidTtl() {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
