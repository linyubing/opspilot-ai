package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WindowGoldTrainerTests {
    @Test
    void trainsOnlyOnTheLatestSettledSamples() {
        var base = mock(GoldTrainer.class);
        var classifier = mock(GoldClassifier.class);
        var rows = samples();
        var names = FeatureProfile.OHLC_20.featureNames();
        when(base.name()).thenReturn("scaled");
        when(base.train(rows.subList(2, 5), names)).thenReturn(classifier);
        var trainer = new WindowGoldTrainer(base, 3);
        assertThat(trainer.train(rows, names)).isSameAs(classifier);
        verify(base).train(rows.subList(2, 5), names);
        assertThat(trainer.name()).isEqualTo("scaled-window-3");
    }

    @Test
    void rejectsShortUnorderedOrRepeatedSamples() {
        var base = mock(GoldTrainer.class);
        var trainer = new WindowGoldTrainer(base, 3);
        var rows = samples();
        assertThatThrownBy(() -> trainer.train(rows.subList(0, 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trainer.train(List.of(rows.get(2), rows.get(1), rows.get(0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trainer.train(List.of(rows.get(1), rows.get(1), rows.get(2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WindowGoldTrainer(base, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(base);
    }

    private List<GoldSample> samples() {
        var values = new HashMap<String, Double>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        var features = new GoldFeatures(values);
        return IntStream.range(0, 5).mapToObj(i -> {
            var day = LocalDate.of(2024, 1, 1).plusDays(i);
            return new GoldSample(day, day.plusDays(1), ForecastHorizon.NEXT_DAY, features,
                    ForecastDirection.BULLISH);
        }).toList();
    }
}
