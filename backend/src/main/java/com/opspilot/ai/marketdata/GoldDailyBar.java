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
        OffsetDateTime collectedAt,
        GoldBarConfirmation confirmation
) {
    /** 旧调用及历史记录没有闭市依据，不能自动视为已确认。 */
    public GoldDailyBar(String symbol, LocalDate priceDate, BigDecimal open,
            BigDecimal high, BigDecimal low, BigDecimal close, String currency,
            String unit, String provider, OffsetDateTime collectedAt) {
        this(symbol, priceDate, open, high, low, close, currency, unit, provider, collectedAt, null);
    }

    /** 判断观察时刻能否使用这根已确认日线。 */
    public boolean isConfirmedAt(OffsetDateTime asOf) {
        if (confirmation == null || asOf == null || priceDate == null || collectedAt == null) {
            return false;
        }
        var proof = confirmation;
        LocalDate today = asOf.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate();
        return GoldBarConfirmation.SOURCE.equals(proof.source())
                && proof.receiptHash() != null && proof.receiptHash().matches("[0-9a-f]{64}")
                && proof.closedDay() != null && proof.checkedAt() != null
                && !proof.closedDay().isAfter(proof.checkedAt()
                        .withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate())
                && !priceDate.isAfter(proof.closedDay())
                && !proof.closedDay().isAfter(today)
                && !proof.checkedAt().isBefore(collectedAt)
                && !proof.checkedAt().isAfter(asOf);
    }
}
