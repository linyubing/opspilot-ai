package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opspilot.ai.marketdata.MarketPrice;

/**
 * 验证从真实候选行情中选择下一有效工作日价格。
 */
class NextValidMarketPriceSelectorTests {

    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.of(2026, 8, 17, 8, 0, 0, 0, ZoneOffset.UTC);

    private final NextValidMarketPriceSelector selector =
            new NextValidMarketPriceSelector();

    @Test
    @DisplayName("周五之后跳过周末并选择周一价格")
    void skipsWeekendAfterFriday() {
        assertThat(selector.select(List.of(
                price("2026-08-15"),
                price("2026-08-16"),
                price("2026-08-17")
        ))).get().extracting(MarketPrice::priceDate)
                .isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    @DisplayName("节假日没有行情记录时选择下一条真实工作日价格")
    void usesNextRecordedWeekdayAfterHolidayGap() {
        assertThat(selector.select(List.of(price("2026-10-08"))))
                .get()
                .extracting(MarketPrice::priceDate)
                .isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("只有周末价格时返回空")
    void returnsEmptyForWeekendOnly() {
        assertThat(selector.select(List.of(
                price("2026-08-15"),
                price("2026-08-16")
        ))).isEmpty();
    }

    @Test
    @DisplayName("候选价格乱序时仍选择最早的有效日期")
    void sortsBeforeSelecting() {
        assertThat(selector.select(List.of(
                price("2026-08-19"),
                price("2026-08-17"),
                price("2026-08-18")
        ))).get().extracting(MarketPrice::priceDate)
                .isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    @DisplayName("没有候选价格时返回空")
    void returnsEmptyForNoCandidates() {
        assertThat(selector.select(List.of())).isEmpty();
    }

    /**
     * 测试价格只验证日期选择，不会写入生产数据库或充当真实行情。
     */
    private MarketPrice price(String date) {
        return new MarketPrice(
                "XAUUSD_TEST",
                LocalDate.parse(date),
                new BigDecimal("100.00000000"),
                "usd",
                "troy_ounce",
                "selector_test",
                COLLECTED_AT
        );
    }
}
