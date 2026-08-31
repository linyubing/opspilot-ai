package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidencePolicyTests {

    private final ConfidencePolicy policy = new ConfidencePolicy(0.55);

    @Test
    void appliesConfidenceThreshold() {
        assertThat(policy.apply(new DirectionProbabilities(0.60, 0.25, 0.15)).direction())
                .isEqualTo(ForecastDirection.BULLISH);
        assertThat(policy.apply(new DirectionProbabilities(0.40, 0.35, 0.25)).status())
                .isEqualTo(SignalStatus.NO_SIGNAL);
        assertThat(policy.apply(new DirectionProbabilities(0.50, 0.50, 0.00)).status())
                .isEqualTo(SignalStatus.NO_SIGNAL);
    }

    @Test
    void trainsMajorityBaseline() {
        GoldClassifier classifier = new MajorityGoldTrainer().train(List.of(
                sample(ForecastDirection.NEUTRAL),
                sample(ForecastDirection.NEUTRAL),
                sample(ForecastDirection.BULLISH)
        ));

        assertThat(classifier.predict(features()))
                .isEqualTo(new DirectionProbabilities(0, 1, 0));
    }

    private GoldSample sample(ForecastDirection direction) {
        return new GoldSample(
                java.time.LocalDate.parse("2026-01-01"),
                java.time.LocalDate.parse("2026-01-02"),
                ForecastHorizon.NEXT_DAY,
                features(),
                direction
        );
    }

    private GoldFeatures features() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        return new GoldFeatures(values);
    }
}
