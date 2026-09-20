package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureScalerTests {
    @Test
    void fitsTrainingOnlyAndDoesNotAdaptToPredictions() {
        // 训练值 1、3 的均值为 2、总体标准差为 1。
        var scaler = FeatureScaler.fit(List.of(sample(1), sample(3)), List.of("gold_return_5"));
        assertThat(scaler.values(features(4))).containsExactly(2);
        scaler.values(features(100_000));
        assertThat(scaler.values(features(4))).containsExactly(2);
    }

    @Test
    void constantTrainingColumnStaysZero() {
        var scaler = FeatureScaler.fit(List.of(sample(2), sample(2)), List.of("gold_return_5"));
        assertThat(scaler.values(features(900))).containsExactly(0);
    }

    @Test
    void independentFitsNeverChangeEarlierScaler() {
        var first = FeatureScaler.fit(List.of(sample(1), sample(3)), List.of("gold_return_5"));
        var second = FeatureScaler.fit(List.of(sample(10), sample(30)), List.of("gold_return_5"));
        assertThat(first.values(features(4))).containsExactly(2);
        assertThat(second.values(features(40))).containsExactly(2);
    }

    @Test
    void refusesInvalidFeatureSets() {
        assertThatThrownBy(() -> FeatureScaler.fit(List.of(), List.of("gold_return_5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureScaler.fit(List.of(sample(1)), List.of("unknown")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureScaler.fit(List.of(sample(1)), List.of("gold_return_5", "gold_return_5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private GoldSample sample(double value) {
        return new GoldSample(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                ForecastHorizon.NEXT_DAY, features(value), ForecastDirection.NEUTRAL);
    }

    private GoldFeatures features(double value) {
        var values = new HashMap<String, Double>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        values.put("gold_return_5", value);
        return new GoldFeatures(values);
    }
}
