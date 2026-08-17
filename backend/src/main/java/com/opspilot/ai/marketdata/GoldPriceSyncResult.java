package com.opspilot.ai.marketdata;

import java.time.LocalDate;

public record GoldPriceSyncResult(
        int receivedCount,
        int savedCount,
        int weekendSkippedCount,
        LocalDate latestPriceDate
) {
}
