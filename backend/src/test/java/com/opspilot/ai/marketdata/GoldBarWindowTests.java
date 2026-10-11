package com.opspilot.ai.marketdata;

import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证配置交易日历的连续性，不把未确认日线认证为闭市行情。 */
class GoldBarWindowTests {

    private final GoldBarWindow window = new GoldBarWindow(calendar());

    @Test
    @DisplayName("任意排序的连续交易日允许正常跨周末")
    void acceptsUnsortedTradingDays() {
        assertThat(window.isContinuous(List.of(bar("2026-10-07"), bar("2026-10-02"),
                bar("2026-10-06"), bar("2026-10-05")))).isTrue();
    }

    @Test
    @DisplayName("缺少中间工作日时拒绝窗口")
    void rejectsMissingTradingDay() {
        assertThat(window.isContinuous(List.of(bar("2026-10-02"), bar("2026-10-07")))).isFalse();
    }

    @Test
    @DisplayName("重复日期不能被排序掩盖")
    void rejectsDuplicateDays() {
        assertThat(window.isContinuous(List.of(bar("2026-10-05"), bar("2026-10-02"),
                bar("2026-10-05")))).isFalse();
    }

    @Test
    @DisplayName("周末记录不是有效交易日")
    void rejectsWeekendDay() {
        assertThat(window.isContinuous(List.of(bar("2026-10-03")))).isFalse();
    }

    @Test
    @DisplayName("配置休市日记录不是有效交易日")
    void rejectsConfiguredHoliday() {
        assertThat(window.isContinuous(List.of(bar("2026-01-01")))).isFalse();
    }

    @Test
    @DisplayName("允许跨越配置休市日和随后周末")
    void acceptsHolidayBoundary() {
        assertThat(window.isContinuous(List.of(bar("2025-12-24"), bar("2025-12-26"),
                bar("2025-12-29")))).isTrue();
    }

    @Test
    @DisplayName("单个有效交易日连续但不代表供应商已经确认")
    void acceptsUnconfirmedTradingDay() {
        GoldDailyBar bar = bar("2026-10-02");
        assertThat(bar.confirmation()).isNull();
        assertThat(window.isContinuous(List.of(bar))).isTrue();
    }

    @Test
    void rejectsEmptyWindow() {
        assertThat(window.isContinuous(List.of())).isFalse();
    }

    @Test
    void rejectsNullWindow() {
        assertThat(window.isContinuous(null)).isFalse();
    }

    @Test
    void rejectsNullBar() {
        assertThat(window.isContinuous(Arrays.asList(bar("2026-10-02"), null))).isFalse();
    }

    @Test
    void rejectsNullDate() {
        assertThat(window.isContinuous(List.of(bar(null)))).isFalse();
    }

    private GoldDailyBar bar(String day) {
        return new GoldDailyBar("XAUUSD", day == null ? null : LocalDate.parse(day),
                new BigDecimal("1800"), new BigDecimal("1810"), new BigDecimal("1790"),
                new BigDecimal("1805"), "usd", "troy_ounce", "twelve_data",
                OffsetDateTime.parse("2026-10-08T01:00:00Z"));
    }

    private static ConfiguredGoldTradingCalendar calendar() {
        ConfiguredGoldTradingCalendar calendar = new ConfiguredGoldTradingCalendar();
        calendar.setHolidays(Set.of("--01-01", "--07-04", "--12-25"));
        return calendar;
    }
}
