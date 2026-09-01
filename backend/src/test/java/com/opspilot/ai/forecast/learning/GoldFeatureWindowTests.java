package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.marketdata.GoldDailyBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金特征窗口计算逻辑。 */
class GoldFeatureWindowTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("数据不足时 returnN 返回 null")
    void returnNInsufficientData() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        assertThat(window.returnN(1)).isNull();
    }

    @Test
    @DisplayName("数据充足时 returnN 返回正确值")
    void returnNCorrect() {
        // 需要 6 根 K 线才能计算 5 日收益率（当前 + 前5日）
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY.minusDays(6), "1790", "1800", "1780", "1795"),
                bar(TODAY.minusDays(5), "1795", "1805", "1785", "1800"),
                bar(TODAY.minusDays(4), "1800", "1810", "1790", "1805"),
                bar(TODAY.minusDays(3), "1805", "1815", "1795", "1810"),
                bar(TODAY.minusDays(2), "1810", "1820", "1800", "1815"),
                bar(TODAY.minusDays(1), "1815", "1825", "1805", "1820"),
                bar(TODAY, "1820", "1830", "1810", "1825")
        ));
        // 收益率 = (1825 - 1800) / 1800 * 100 ≈ 1.3889%
        BigDecimal result = window.returnN(5);
        assertThat(result).isNotNull();
        assertThat(result.doubleValue()).isCloseTo(1.3889, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("overnightGap 计算正确")
    void overnightGapCorrect() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY.minusDays(1), "1800", "1810", "1790", "1800"),
                bar(TODAY, "1810", "1820", "1800", "1815")
        ));
        // 隔夜缺口 = (1810 - 1800) / 1800 * 100 ≈ 0.5556%
        BigDecimal result = window.overnightGap();
        assertThat(result).isNotNull();
        assertThat(result).isBetween(new BigDecimal("0.55"), new BigDecimal("0.56"));
    }

    @Test
    @DisplayName("intradayReturn 计算正确")
    void intradayReturnCorrect() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        // 日内收益率 = (1805 - 1800) / 1800 * 100 ≈ 0.2778%
        BigDecimal result = window.intradayReturn();
        assertThat(result).isNotNull();
        assertThat(result).isBetween(new BigDecimal("0.27"), new BigDecimal("0.28"));
    }

    @Test
    @DisplayName("dailyRange 计算正确")
    void dailyRangeCorrect() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        // 日内波幅 = (1810 - 1790) / 1800 * 100 ≈ 1.1111%
        BigDecimal result = window.dailyRange();
        assertThat(result).isNotNull();
        assertThat(result).isBetween(new BigDecimal("1.11"), new BigDecimal("1.12"));
    }

    @Test
    @DisplayName("closeLocation high == low 时返回 0.5")
    void closeLocationWhenHighEqualsLow() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1800", "1800", "1800")
        ));
        BigDecimal result = window.closeLocation();
        assertThat(result).isNotNull();
        assertThat(result).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("atr14 数据不足时返回 null")
    void atr14InsufficientData() {
        List<GoldDailyBar> bars = List.of(
                bar(TODAY.minusDays(19), "1800", "1810", "1790", "1805")
        );
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, bars);
        assertThat(window.atr14()).isNull();
    }

    @Test
    @DisplayName("atr14 数据充足时返回正值")
    void atr14Correct() {
        List<GoldDailyBar> bars = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> bar(TODAY.minusDays(20 - i),
                        String.valueOf(1800 + i), String.valueOf(1810 + i),
                        String.valueOf(1790 + i), String.valueOf(1805 + i)))
                .toList();
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, bars);
        BigDecimal atr = window.atr14();
        assertThat(atr).isNotNull();
        assertThat(atr).isPositive();
    }

    @Test
    @DisplayName("volatility 数据不足时返回 null")
    void volatilityInsufficientData() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        assertThat(window.volatility(20)).isNull();
    }

    @Test
    @DisplayName("maDistance 数据不足时返回 null")
    void maDistanceInsufficientData() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        assertThat(window.maDistance(5)).isNull();
    }

    @Test
    @DisplayName("rsi14 数据不足时返回 null")
    void rsi14InsufficientData() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        assertThat(window.rsi14()).isNull();
    }

    @Test
    @DisplayName("drawdown20 数据不足时返回 null")
    void drawdown20InsufficientData() {
        GoldFeatureWindow window = new GoldFeatureWindow(TODAY, List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        assertThat(window.drawdown20()).isNull();
    }

    private GoldDailyBar bar(LocalDate date, String open, String high, String low, String close) {
        return new GoldDailyBar(
                "XAUUSD",
                date,
                new BigDecimal(open),
                new BigDecimal(high),
                new BigDecimal(low),
                new BigDecimal(close),
                "USD",
                "troy_oz",
                "twelve_data",
                java.time.OffsetDateTime.now()
        );
    }
}
