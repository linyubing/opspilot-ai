package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.MarketPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record GoldPriceResponse(
        String symbol,
        LocalDate priceDate,
        BigDecimal referencePrice,
        String currency,
        String unit,
        String provider,
        OffsetDateTime collectedAt
) {
    public static GoldPriceResponse from(MarketPrice price) {
        return new GoldPriceResponse(
                price.symbol(),
                price.priceDate(),
                price.referencePrice(),
                price.currency(),
                price.unit(),
                price.provider(),
                price.collectedAt()
        );
    }
}
