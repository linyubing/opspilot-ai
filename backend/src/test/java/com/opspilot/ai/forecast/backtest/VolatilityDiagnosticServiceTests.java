package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VolatilityDiagnosticServiceTests {

    @Test
    void diagnosesWithPastThresholds() {
        List<VolatilitySample> samples = new ArrayList<>();
        LocalDate start = LocalDate.parse("2026-01-01");
        for (int index = 1; index <= 20; index++) {
            samples.add(item(start.plusDays(index), index,
                    ForecastDirection.NEUTRAL, ForecastDirection.NEUTRAL));
        }
        samples.add(item(start.plusDays(21), 5,
                ForecastDirection.BULLISH, ForecastDirection.BULLISH));
        samples.add(item(start.plusDays(22), 10,
                ForecastDirection.BULLISH, ForecastDirection.BEARISH));
        samples.add(item(start.plusDays(23), 25,
                ForecastDirection.BEARISH, ForecastDirection.BEARISH));

        List<VolatilityDiagnostic> result =
                new VolatilityDiagnosticService().diagnose(samples);

        assertThat(result).extracting(VolatilityDiagnostic::regime)
                .containsExactly(
                        VolatilityRegime.LOW,
                        VolatilityRegime.MEDIUM,
                        VolatilityRegime.HIGH
                );
        assertThat(result).extracting(VolatilityDiagnostic::accuracy)
                .containsExactly(
                        new BigDecimal("1.0000"),
                        new BigDecimal("0.0000"),
                        new BigDecimal("1.0000")
                );
    }

    private VolatilitySample item(
            LocalDate date,
            int volatility,
            ForecastDirection predicted,
            ForecastDirection actual
    ) {
        return new VolatilitySample(
                date, BigDecimal.valueOf(volatility), predicted, actual
        );
    }
}
