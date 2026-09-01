package com.opspilot.ai.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证配置的黄金交易日历能正确跳过周末和固定休市日。 */
class ConfiguredGoldTradingCalendarTests {

    private final ConfiguredGoldTradingCalendar calendar =
            new ConfiguredGoldTradingCalendar();

    @Test
    @DisplayName("周五后返回下周一")
    void skipsWeekend() {
        // 2026-08-28 是周五
        assertThat(calendar.nextBusinessDay(LocalDate.parse("2026-08-28")))
                .isEqualTo(LocalDate.parse("2026-08-31"));
    }

    @Test
    @DisplayName("跳过固定休市日")
    void skipsHoliday() {
        calendar.setHolidays(java.util.Set.of(MonthDay.parse("--08-31")));
        // 08-31 是周一，但是休市，应跳到 09-01
        assertThat(calendar.nextBusinessDay(LocalDate.parse("2026-08-30")))
                .isEqualTo(LocalDate.parse("2026-09-01"));
    }

    @Test
    @DisplayName("工作日直接返回第二天")
    void normalWorkday() {
        assertThat(calendar.nextBusinessDay(LocalDate.parse("2026-08-26")))
                .isEqualTo(LocalDate.parse("2026-08-27"));
    }

    @Test
    @DisplayName("休市日落在周六时跳到下周一")
    void holidayOnWeekend() {
        calendar.setHolidays(java.util.Set.of(MonthDay.parse("--08-29")));
        // 08-28 周五 → 下一天 08-29 周六（周末），再下一天 08-30 周日（周末），再下一天 08-31 周一（但 08-29 是周六本身已是周末，不会因休市日跳）
        // 实际：from=08-28, next=08-29（周六，周末），跳到 08-30（周日，周末），跳到 08-31（周一，非休市）→08-31
        assertThat(calendar.nextBusinessDay(LocalDate.parse("2026-08-28")))
                .isEqualTo(LocalDate.parse("2026-08-31"));
    }
}
