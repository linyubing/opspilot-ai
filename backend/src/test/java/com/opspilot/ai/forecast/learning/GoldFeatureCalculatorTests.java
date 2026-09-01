package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.extension.ExtendWith;

/** 验证黄金特征计算器。 */
@ExtendWith(MockitoExtension.class)
class GoldFeatureCalculatorTests {

    @Mock
    private GoldDailyBarRepository repository;

    @InjectMocks
    private GoldFeatureCalculator calculator;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("数据不足 20 根 K 线时返回 null")
    void returnsNullWhenInsufficientData() {
        when(repository.findAll(anyString(), anyString())).thenReturn(List.of(
                bar(TODAY, "1800", "1810", "1790", "1805")
        ));
        assertThat(calculator.compute(TODAY)).isNull();
    }

    @Test
    @DisplayName("数据充足时计算所有 OHLC 特征")
    void computesAllFeatures() {
        List<GoldDailyBar> bars = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> bar(TODAY.minusDays(24 - i),
                        String.valueOf(1800 + i), String.valueOf(1810 + i),
                        String.valueOf(1790 + i), String.valueOf(1805 + i)))
                .toList();
        when(repository.findAll(anyString(), anyString())).thenReturn(bars);

        GoldFeatures features = calculator.compute(TODAY);

        assertThat(features).isNotNull();
        assertThat(features.values()).containsKeys(
                "return1", "return3", "return5", "return10", "return20",
                "overnightGap", "intradayReturn", "dailyRange", "closeLocation",
                "atr14", "volatility5", "volatility20",
                "ma5Distance", "ma20Distance", "ma5Slope", "ma20Slope",
                "rsi14", "drawdown20", "highBreakout20", "lowBreakdown20"
        );
        assertThat(features.values().get("return1")).isFinite();
        assertThat(features.values().get("atr14")).isPositive();
        assertThat(features.values().get("rsi14")).isBetween(0.0, 100.0);
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
