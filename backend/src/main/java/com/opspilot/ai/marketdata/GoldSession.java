package com.opspilot.ai.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** 黄金供应商标签对应的候选时段，不是官方交易日历。 */
public record GoldSession(LocalDate date, Instant start, Instant end) {
    private static final ZoneId ZONE = ZoneId.of("Australia/Sydney");

    public static GoldSession forDate(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("黄金标签日期不能为空");
        // 两个本地端点分别转换，不能给start直接加24小时：夏令时会产生23/25小时。
        Instant start = date.atTime(LocalTime.of(7, 0)).atZone(ZONE).toInstant();
        Instant end = date.plusDays(1).atTime(LocalTime.of(7, 0)).atZone(ZONE).toInstant();
        return new GoldSession(date, start, end);
    }
}
