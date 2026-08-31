package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastEvaluatorTests {

    @Test
    void calculatesCoveredMetrics() {
        ForecastMetrics metrics = new ForecastEvaluator().evaluate(List.of(
                settled(ForecastDirection.BULLISH, ForecastDirection.BULLISH),
                settled(ForecastDirection.BULLISH, ForecastDirection.NEUTRAL),
                noSignal(ForecastDirection.BULLISH),
                settled(ForecastDirection.NEUTRAL, ForecastDirection.NEUTRAL),
                settled(ForecastDirection.NEUTRAL, ForecastDirection.BULLISH),
                settled(ForecastDirection.BEARISH, ForecastDirection.BEARISH)
        ));

        assertThat(metrics.sampleCount()).isEqualTo(6);
        assertThat(metrics.coveredCount()).isEqualTo(5);
        assertThat(metrics.coverage()).isEqualByComparingTo("0.8333");
        assertThat(metrics.accuracy()).isEqualByComparingTo("0.6000");
        assertThat(metrics.balancedAccuracy()).isEqualByComparingTo("0.6667");
    }

    @Test
    void rejectsPromotionWhenAClassIsMissing() {
        ForecastMetrics metrics = new ForecastEvaluator().evaluate(List.of(
                settled(ForecastDirection.BULLISH, ForecastDirection.BULLISH),
                settled(ForecastDirection.NEUTRAL, ForecastDirection.NEUTRAL)
        ));

        assertThat(metrics.recalls().get(ForecastDirection.BEARISH)).isNull();
        assertThat(metrics.balancedAccuracy()).isNull();
        assertThat(metrics.promotionReady()).isFalse();
    }

    private SettledPrediction settled(
            ForecastDirection actual,
            ForecastDirection predicted
    ) {
        DirectionProbabilities probabilities = switch (predicted) {
            case BULLISH -> new DirectionProbabilities(0.8, 0.1, 0.1);
            case NEUTRAL -> new DirectionProbabilities(0.1, 0.8, 0.1);
            case BEARISH -> new DirectionProbabilities(0.1, 0.1, 0.8);
        };
        return new SettledPrediction(
                LocalDate.parse("2026-01-01"),
                probabilities,
                new GoldPrediction(SignalStatus.PREDICTED, predicted, 0.8),
                actual
        );
    }

    private SettledPrediction noSignal(ForecastDirection actual) {
        return new SettledPrediction(
                LocalDate.parse("2026-01-01"),
                new DirectionProbabilities(0.4, 0.35, 0.25),
                new GoldPrediction(SignalStatus.NO_SIGNAL, null, 0.4),
                actual
        );
    }
}
