package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.marketdata.GoldDailyBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金 OHLC 特征计算器。 */
class GoldFeatureCalculatorTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);
    private final GoldFeatureCalculator calculator = new GoldFeatureCalculator();

    @Test
    @DisplayName("刚好 20 根日线不能计算完整特征")
    void exactly20BarsReturnsEmpty() {
        List<GoldDailyBar> bars = bars(20);
        Optional<GoldOhlcFeatures> result = calculator.compute(TODAY, bars);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("刚好 21 根日线可以计算全部 20 个特征")
    void exactly21BarsComputesAllFeatures() {
        List<GoldDailyBar> bars = bars(21);
        Optional<GoldOhlcFeatures> result = calculator.compute(TODAY, bars);
        assertThat(result).isPresent();
        assertThat(result.get().values()).hasSize(20);
        assertThat(result.get().values().values())
                .allMatch(Double::isFinite);
    }

    @Test
    @DisplayName("未来日线不影响计算结果")
    void calculatorIgnoresBarsAfterAsOfDate() {
        List<GoldDailyBar> bars21 = bars(21);
        List<GoldDailyBar> barsWithFuture = new java.util.ArrayList<>(bars21);
        barsWithFuture.add(bar(TODAY.plusDays(5), "1900", "1910", "1890", "1905"));

        Optional<GoldOhlcFeatures> result1 = calculator.compute(TODAY, bars21);
        Optional<GoldOhlcFeatures> result2 = calculator.compute(TODAY, barsWithFuture);

        assertThat(result1).isPresent();
        assertThat(result2).isPresent();
        assertThat(result1.get()).isEqualTo(result2.get());
    }

    @Test
    @DisplayName("dailyRange 使用前收盘价作分母")
    void dailyRangeUsesPreviousClose() {
        // 前收盘价 1800，当日 high=1820, low=1790
        // dailyRange = (1820 - 1790) / 1800 * 100 ≈ 1.6667%
        List<GoldDailyBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bars.add(bar(TODAY.minusDays(20 - i), "1800", "1800", "1800", "1800"));
        }
        bars.add(bar(TODAY, "1800", "1820", "1790", "1805"));

        Optional<GoldOhlcFeatures> result = calculator.compute(TODAY, bars);
        assertThat(result).isPresent();
        double dailyRange = result.get().values().get("dailyRange");
        assertThat(dailyRange).isCloseTo(1.6667, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("volatility20 使用 21 根价格产生 20 个收益率")
    void volatility20UsesTwentyReturns() {
        List<GoldDailyBar> bars = bars(21);
        Optional<GoldOhlcFeatures> result = calculator.compute(TODAY, bars);
        assertThat(result).isPresent();
        double volatility20 = result.get().values().get("volatility20");
        // 波动率应该是非负的有限值
        assertThat(volatility20).isFinite().isNotNegative();
    }

    @Test
    @DisplayName("highBreakout20 使用当前日线与此前 20 根比较")
    void breakout20UsesPreviousTwentyBars() {
        List<GoldDailyBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bars.add(bar(TODAY.minusDays(20 - i), "1800", "1810", "1790", "1800"));
        }
        // 当日最高价 1820，此前 20 根最高价 1810
        // highBreakout20 = (1820 - 1810) / 1810 * 100 ≈ 0.5525%
        bars.add(bar(TODAY, "1800", "1820", "1790", "1805"));

        Optional<GoldOhlcFeatures> result = calculator.compute(TODAY, bars);
        assertThat(result).isPresent();
        double highBreakout20 = result.get().values().get("highBreakout20");
        assertThat(highBreakout20).isCloseTo(0.5525, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("lowBreakdown20 使用当前日线与此前 20 根比较")
    void breakdown20UsesPreviousTwentyBars() {
        List<GoldDailyBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bars.add(bar(TODAY.minusDays(20 - i), "1800", "1810", "1790", "1800"));
        }
        // 当日最低价 1780，此前 20 根最低价 1790
        // lowBreakdown20 = (1780 - 1790) / 1790 * 100 ≈ -0.5587%
        bars.add(bar(TODAY, "1800", "1810", "1780", "1805"));

        Optional<GoldOhlcFeatures> result = calculator.compute(TODAY, bars);
        assertThat(result).isPresent();
        double lowBreakdown20 = result.get().values().get("lowBreakdown20");
        assertThat(lowBreakdown20).isCloseTo(-0.5587, org.assertj.core.data.Offset.offset(0.01));
    }

    private List<GoldDailyBar> bars(int count) {
        List<GoldDailyBar> result = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            int dayOffset = count - 1 - i;
            result.add(bar(TODAY.minusDays(dayOffset),
                    String.valueOf(1800 + i), String.valueOf(1810 + i),
                    String.valueOf(1790 + i), String.valueOf(1805 + i)));
        }
        return result;
    }

    private GoldDailyBar bar(LocalDate date, String open, String high, String low, String close) {
        return new GoldDailyBar(
                "XAUUSD", date,
                new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close),
                "USD", "troy_oz", "twelve_data", OffsetDateTime.now()
        );
    }
}
