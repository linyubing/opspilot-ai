package com.opspilot.ai.marketdata.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;

import java.time.LocalDate;

/** 向客户端返回黄金 OHLC 日线同步结果。 */
public record GoldDailyBarSyncResponse(
        int receivedCount,
        int savedCount,
        int weekendSkippedCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate latestPriceDate
) {

    public static GoldDailyBarSyncResponse from(GoldDailyBarSyncResult result) {
        return new GoldDailyBarSyncResponse(
                result.receivedCount(),
                result.savedCount(),
                result.weekendSkippedCount(),
                result.latestPriceDate()
        );
    }
}
