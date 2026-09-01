package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastMetricsTests {

    @Test
    @DisplayName("记录包含 logLoss 字段")
    void includesLogLoss() {
        ForecastMetrics metrics = sampleMetrics();

        assertThat(metrics.logLoss()).isEqualByComparingTo("1.2000");
    }

    @Test
    @DisplayName("logLoss 可以为 null")
    void logLossCanBeNull() {
        ForecastMetrics metrics = new ForecastMetrics(
                10, 10, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, null, recalls(), matrix(), true
        );

        assertThat(metrics.logLoss()).isNull();
    }

    private ForecastMetrics sampleMetrics() {
        return new ForecastMetrics(
                240, 200,
                new BigDecimal("0.8333"),
                new BigDecimal("0.6000"),
                new BigDecimal("0.6000"),
                new BigDecimal("0.4500"),
                new BigDecimal("1.2000"),
                recalls(),
                matrix(),
                true
        );
    }

    private Map<ForecastDirection, BigDecimal> recalls() {
        Map<ForecastDirection, BigDecimal> recalls = new EnumMap<>(ForecastDirection.class);
        for (ForecastDirection direction : ForecastDirection.values()) {
            recalls.put(direction, new BigDecimal("0.6000"));
        }
        return recalls;
    }

    private Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix() {
        Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix =
                new EnumMap<>(ForecastDirection.class);
        for (ForecastDirection direction : ForecastDirection.values()) {
            matrix.put(direction, Map.of(direction, 1));
        }
        return matrix;
    }
}
