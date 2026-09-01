package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.GoldDailyBar;
import org.junit.jupiter.api.DisplayName;
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

/** 验证回测日期按时间切分为开发集和留出集。 */
class BacktestDateSelectorTests {

    private static final LocalDate START = LocalDate.parse("2020-01-01");
    private static final int WARMUP = 21;

    private final BacktestDateSelector selector = new BacktestDateSelector();

    @Test
    @DisplayName("DEFAULT 只使用开发集日期")
    void defaultUsesOnlyDevelopmentPeriod() {
        // 100 个日期，排除前 21 和最后 1，eligible = 78
        // 开发集 = 前 80% = 62 个
        List<LocalDate> dates = selector.select(prices(100), 5);
        List<LocalDate> allEligible = eligibleDates(100);
        List<LocalDate> development = allEligible.subList(0, (int) Math.floor(allEligible.size() * 0.8));

        assertThat(dates).allMatch(development::contains);
    }

    @Test
    @DisplayName("HOLDOUT 只使用留出集日期")
    void holdoutUsesOnlyFinalTwentyPercent() {
        List<LocalDate> holdout = selector.select(prices(100), 5, BacktestSampleSet.HOLDOUT);
        List<LocalDate> allEligible = eligibleDates(100);
        List<LocalDate> holdoutPeriod = allEligible.subList(
                (int) Math.floor(allEligible.size() * 0.8),
                allEligible.size()
        );

        assertThat(holdout).allMatch(holdoutPeriod::contains);
    }

    @Test
    @DisplayName("DEFAULT 和 HOLDOUT 没有重叠")
    void defaultAndHoldoutDoNotOverlap() {
        List<LocalDate> dates = selector.select(prices(100), 5);
        List<LocalDate> holdout = selector.select(
                prices(100), 5, BacktestSampleSet.HOLDOUT
        );

        assertThat(dates).doesNotContainAnyElementsOf(holdout);
    }

    @Test
    @DisplayName("每个开发日期都早于留出日期")
    void everyDevelopmentDateIsBeforeHoldout() {
        List<LocalDate> dates = selector.select(prices(100), 5);
        List<LocalDate> holdout = selector.select(
                prices(100), 5, BacktestSampleSet.HOLDOUT
        );

        LocalDate maxDevelopment = dates.stream().max(LocalDate::compareTo).orElseThrow();
        LocalDate minHoldout = holdout.stream().min(LocalDate::compareTo).orElseThrow();

        assertThat(maxDevelopment).isBefore(minHoldout);
    }

    @Test
    @DisplayName("RECENT 使用最近的 eligible 日期")
    void recentUsesLatestEligibleDates() {
        List<LocalDate> recent = selector.select(
                prices(100), 5, BacktestSampleSet.RECENT
        );
        List<LocalDate> allEligible = eligibleDates(100);

        assertThat(recent).hasSize(5);
        assertThat(recent).containsExactly(
                allEligible.get(allEligible.size() - 5),
                allEligible.get(allEligible.size() - 4),
                allEligible.get(allEligible.size() - 3),
                allEligible.get(allEligible.size() - 2),
                allEligible.get(allEligible.size() - 1)
        );
    }

    @Test
    @DisplayName("拒绝超过分区大小的样本数")
    void rejectsSamplesLargerThanPartition() {
        // 100 个日期，eligible = 78，开发集 = 62
        assertThatThrownBy(() -> selector.select(prices(100), 63))
                .isInstanceOf(BacktestDataInsufficientException.class)
                .hasMessageContaining("开发集日期不足");
    }

    @Test
    @DisplayName("选择结果是确定性的")
    void selectionIsDeterministic() {
        List<LocalDate> first = selector.select(prices(100), 5);
        List<LocalDate> second = selector.select(prices(100), 5);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("排除预热期和最后未结算日期")
    void excludesWarmupAndLastUnsettledDate() {
        List<LocalDate> dates = selector.select(prices(100), 5);
        List<LocalDate> allEligible = eligibleDates(100);

        // 不能包含前 21 个日期
        for (int i = 0; i < WARMUP; i++) {
            assertThat(dates).doesNotContain(START.plusDays(i));
        }
        // 不能包含最后一个日期（索引 99）
        assertThat(dates).doesNotContain(START.plusDays(99));
    }

    @Test
    void selectsDatesFromRealGoldBars() {
        assertThat(selector.selectBars(
                bars(100),
                5,
                BacktestSampleSet.DEFAULT
        )).hasSize(5);
    }

    @Test
    void sortsAndRemovesRepeatedDatesBeforeSelecting() {
        List<MarketPrice> input = new ArrayList<>(prices(100));
        input.add(price(START.plusDays(50)));
        Collections.reverse(input);

        assertThat(selector.select(input, 5)).hasSize(5);
    }

    @Test
    void rejectsInsufficientUniqueDates() {
        assertThatThrownBy(() -> selector.select(prices(25), 5))
                .isInstanceOf(BacktestDataInsufficientException.class)
                .hasMessageContaining("不足");
    }

    @Test
    void rejectsSampleCountOutsideRange() {
        assertThatThrownBy(() -> selector.select(prices(100), 0))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
        assertThatThrownBy(() -> selector.select(prices(141), 121))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("1 到 120");
    }

    /** 计算 eligible 日期列表（排除前 warmup 和最后 1 根） */
    private List<LocalDate> eligibleDates(int totalDates) {
        List<LocalDate> all = new ArrayList<>();
        for (int i = 0; i < totalDates; i++) {
            all.add(START.plusDays(i));
        }
        return all.subList(WARMUP, totalDates - 1);
    }

    private List<MarketPrice> prices(int count) {
        List<MarketPrice> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(price(START.plusDays(index)));
        }
        return result;
    }

    private List<GoldDailyBar> bars(int count) {
        List<GoldDailyBar> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("2500");
            result.add(new GoldDailyBar(
                    "XAUUSD", START.plusDays(index), close,
                    close.add(BigDecimal.TEN),
                    close.subtract(BigDecimal.TEN), close,
                    "usd", "troy_ounce", "twelve_data",
                    OffsetDateTime.of(
                            2026, 8, 28, 8, 0, 0, 0, ZoneOffset.UTC
                    )
            ));
        }
        return result;
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
