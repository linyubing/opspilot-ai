package com.opspilot.ai.marketdata.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opspilot.ai.marketdata.GoldDailyBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 向客户端返回一根真实黄金 OHLC 日线。 */
public record GoldDailyBarResponse(
        String symbol,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
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

    public static GoldDailyBarResponse from(GoldDailyBar bar) {
        return new GoldDailyBarResponse(
                bar.symbol(),
                bar.priceDate(),
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                bar.currency(),
                bar.unit(),
                bar.provider(),
                bar.collectedAt()
        );
    }
}
