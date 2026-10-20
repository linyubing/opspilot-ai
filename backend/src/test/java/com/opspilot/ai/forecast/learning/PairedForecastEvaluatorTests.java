package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PairedForecastEvaluatorTests {
    private final PairedForecastEvaluator evaluator = new PairedForecastEvaluator(new ForecastEvaluator());

    @Test
    void scoresBaselineOnlyOnCandidateSignalDates() {
        // 基线全日期3/4正确，同候选信号日期却只有1/2，二者不可混用。
        var model = List.of(
                row(1, ForecastDirection.NEUTRAL, .8, .1, .1),
                row(2, ForecastDirection.NEUTRAL, .3, .4, .3),
                row(3, ForecastDirection.NEUTRAL, .4, .35, .25),
                row(4, ForecastDirection.BEARISH, .1, .1, .8));
        var baseline = model.stream().map(r -> row(r.asOfDate().getDayOfMonth(),
                r.actual(), .01, .98, .01)).toList();
        var paired = evaluator.evaluate(model, baseline);
        assertThat(new ForecastEvaluator().evaluate(baseline).accuracy()).isEqualByComparingTo("0.75");
        assertThat(paired.all().sampleCount()).isEqualTo(4);
        assertThat(paired.all().coveredCount()).isEqualTo(4);
        assertThat(paired.all().accuracy()).isEqualByComparingTo("0.5");
        assertThat(paired.signalDates()).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 4));
        assertThat(paired.baselineOnSignals().sampleCount()).isEqualTo(2);
        assertThat(paired.baselineOnSignals().accuracy()).isEqualByComparingTo("0.5");
    }

    @Test
    void rejectsDateMismatchEvenWhenCountsAgree() {
        var model = List.of(row(1, ForecastDirection.NEUTRAL, .8, .1, .1));
        var baseline = List.of(row(2, ForecastDirection.NEUTRAL, .1, .8, .1));
        assertThatThrownBy(() -> evaluator.evaluate(model, baseline))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("日期");
    }

    @Test
    void rejectsLabelMismatchOnSameDate() {
        var model = List.of(row(1, ForecastDirection.NEUTRAL, .8, .1, .1));
        var baseline = List.of(row(1, ForecastDirection.BULLISH, .1, .8, .1));
        assertThatThrownBy(() -> evaluator.evaluate(model, baseline))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("标签");
    }

    @Test
    void rejectsDuplicateDates() {
        var repeated = List.of(row(1, ForecastDirection.NEUTRAL, .8, .1, .1),
                row(1, ForecastDirection.NEUTRAL, .8, .1, .1));
        assertThatThrownBy(() -> evaluator.evaluate(repeated, repeated))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }

    @Test
    void keepsTieAsNoSignalButUsesFixedDirectionForFullScore() {
        var tied = List.of(row(1, ForecastDirection.BEARISH, .5, .5, 0));
        var paired = evaluator.evaluate(tied, tied);
        assertThat(paired.signalDates()).isEmpty();
        assertThat(paired.baselineOnSignals()).isNull();
        assertThat(paired.all().coveredCount()).isEqualTo(1);
        assertThat(paired.all().accuracy()).isEqualByComparingTo("0");
        assertThat(paired.all().confusionMatrix().get(ForecastDirection.BEARISH)
                .get(ForecastDirection.BULLISH)).isEqualTo(1);
    }

    private SettledPrediction row(int day, ForecastDirection actual, double up, double neutral, double down) {
        var probabilities = new DirectionProbabilities(up, neutral, down);
        return new SettledPrediction(LocalDate.of(2025, 1, day), probabilities,
                new ConfidencePolicy(.55).apply(probabilities), actual);
    }
}
