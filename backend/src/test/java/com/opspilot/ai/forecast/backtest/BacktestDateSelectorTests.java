package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.marketdata.MarketPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证回测日期按完整历史均匀分层，并排除指标预热期和待结算日期。 */
class BacktestDateSelectorTests {

    private static final LocalDate START = LocalDate.parse("2020-01-01");

    private final BacktestDateSelector selector = new BacktestDateSelector();

    @Test
    void selectsDatesAcrossTheWholeHistory() {
        assertThat(selector.select(prices(101), 5)).containsExactly(
                dateAt(20), dateAt(40), dateAt(60),
                dateAt(79), dateAt(99)
        );
    }

    @Test
    void selectsMiddleDateForOneSample() {
        assertThat(selector.select(prices(101), 1))
                .containsExactly(dateAt(59));
    }

    @Test
    void sortsAndRemovesRepeatedDatesBeforeSelecting() {
        List<MarketPrice> input = new ArrayList<>(prices(26));
        input.add(price(dateAt(10)));
        Collections.reverse(input);

        assertThat(selector.select(input, 5)).containsExactly(
                dateAt(20), dateAt(21), dateAt(22),
                dateAt(23), dateAt(24)
        );
    }

    @Test
    void rejectsInsufficientUniqueDates() {
        assertThatThrownBy(() -> selector.select(prices(25), 5))
                .isInstanceOf(BacktestDataInsufficientException.class)
                .hasMessageContaining("需要=26")
                .hasMessageContaining("实际=25");
    }

    @Test
    void rejectsSampleCountOutsideRange() {
        assertThatThrownBy(() -> selector.select(prices(121), 0))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
        assertThatThrownBy(() -> selector.select(prices(141), 121))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
    }

    private List<MarketPrice> prices(int count) {
        List<MarketPrice> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(price(dateAt(index)));
        }
        return result;
    }

    private LocalDate dateAt(int index) {
        return START.plusDays(index);
    }

    private MarketPrice price(LocalDate date) {
        return new MarketPrice(
                "XAUUSD", date, new BigDecimal("2500"),
                "usd", "troy_ounce", "test",
                OffsetDateTime.of(
                        2026, 8, 28, 8, 0, 0, 0, ZoneOffset.UTC
                )
        );
    }
}
