package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ClassWeightsTests {
    @Test
    void balancesTotalLossFromTrainingCounts() {
        var weights = ClassWeights.fit(List.of(sample(ForecastDirection.BULLISH), sample(ForecastDirection.BULLISH),
                sample(ForecastDirection.NEUTRAL), sample(ForecastDirection.BEARISH),
                sample(ForecastDirection.BEARISH), sample(ForecastDirection.BEARISH)));
        assertThat(weights.get(ForecastDirection.BULLISH)).isEqualTo(1.0f);
        assertThat(weights.get(ForecastDirection.NEUTRAL)).isEqualTo(2.0f);
        assertThat(weights.get(ForecastDirection.BEARISH)).isCloseTo(2.0f / 3, within(0.000001f));
        assertThatThrownBy(() -> weights.put(ForecastDirection.NEUTRAL, 10f))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesMissingClassesInsteadOfInventingSamples() {
        assertThatThrownBy(() -> ClassWeights.fit(List.of(sample(ForecastDirection.BULLISH))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClassWeights.fit(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachFitUsesOnlyItsOwnTrainingCounts() {
        var oneEach = List.of(sample(ForecastDirection.BULLISH), sample(ForecastDirection.NEUTRAL),
                sample(ForecastDirection.BEARISH));
        var first = ClassWeights.fit(oneEach);
        ClassWeights.fit(List.of(oneEach.get(0), oneEach.get(0), oneEach.get(1), oneEach.get(2)));
        assertThat(first.values()).containsOnly(1f);
    }

    private GoldSample sample(ForecastDirection direction) {
        var values = new HashMap<String, Double>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        return new GoldSample(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                ForecastHorizon.NEXT_DAY, new GoldFeatures(values), direction);
    }
}
