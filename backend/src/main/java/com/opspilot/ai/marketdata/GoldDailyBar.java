package com.opspilot.ai.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 保存一根真实黄金日线及其明确的开高低收价格。 */
public record GoldDailyBar(
        String symbol,
        LocalDate priceDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        String currency,
        String unit,
        String provider,
        OffsetDateTime collectedAt
) {
}
