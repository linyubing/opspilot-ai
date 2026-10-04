package com.opspilot.ai.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/** 保存用于时段核验的小时开高低收，不自动补齐或舍入。 */
public record GoldHourBar(Instant start, BigDecimal open, BigDecimal high,
        BigDecimal low, BigDecimal close) {
    public GoldHourBar {
        if (start == null || !validPrices(open, high, low, close)) {
            throw new IllegalArgumentException("黄金小时线时间或价格无效");
        }
    }

    static boolean validPrices(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return open != null && high != null && low != null && close != null
                && open.signum() > 0 && high.signum() > 0 && low.signum() > 0 && close.signum() > 0
                && high.compareTo(low) >= 0 && high.compareTo(open) >= 0 && high.compareTo(close) >= 0
                && low.compareTo(open) <= 0 && low.compareTo(close) <= 0;
    }
}
