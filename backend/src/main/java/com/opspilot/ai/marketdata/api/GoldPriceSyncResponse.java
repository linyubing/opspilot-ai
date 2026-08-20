package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.GoldPriceSyncResult;

import java.time.LocalDate;

public record GoldPriceSyncResponse(
        int receivedCount,
        int savedCount,
        int weekendSkippedCount,
        LocalDate latestPriceDate
) {
    public static GoldPriceSyncResponse from(GoldPriceSyncResult result) {
        return new GoldPriceSyncResponse(
                result.receivedCount(),
                result.savedCount(),
                result.weekendSkippedCount(),
                result.latestPriceDate()
        );
    }
}
