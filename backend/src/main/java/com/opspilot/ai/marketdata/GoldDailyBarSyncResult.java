package com.opspilot.ai.marketdata;

import java.time.LocalDate;

/** 汇总一次黄金 OHLC 日线同步的处理数量。 */
public record GoldDailyBarSyncResult(
        int receivedCount,
        int savedCount,
        int weekendSkippedCount,
        LocalDate latestPriceDate
) {
}
