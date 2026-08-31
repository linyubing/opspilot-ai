package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证黄金统计预测基础类型的固定周期和数据校验。 */
class GoldLearningTypesTests {

    @Test
    void definesTradingDayHorizons() {
        assertThat(ForecastHorizon.NEXT_DAY.sessions()).isEqualTo(1);
        assertThat(ForecastHorizon.FIVE_DAYS.sessions()).isEqualTo(5);
        assertThat(ForecastHorizon.TWENTY_DAYS.sessions()).isEqualTo(20);
    }

    @Test
    void rejectsIncompleteFeatures() {
        assertThatThrownBy(() -> new GoldFeatures(Map.of("gold_return_1", 1.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
    }

    @Test
    void rejectsInvalidProbabilities() {
        assertThatThrownBy(() -> new DirectionProbabilities(0.5, 0.4, 0.2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("总和");
    }

    @Test
    void keepsNoSignalSeparateFromNeutral() {
        GoldPrediction prediction = new GoldPrediction(
                SignalStatus.NO_SIGNAL,
                null,
                0.45
        );

        assertThat(prediction.status()).isEqualTo(SignalStatus.NO_SIGNAL);
        assertThat(prediction.direction()).isNull();
        assertThatThrownBy(() -> new GoldPrediction(
                SignalStatus.PREDICTED,
                null,
                0.60
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTargetDateBeforeAnalysisDate() {
        LocalDate date = LocalDate.parse("2026-08-28");

        assertThatThrownBy(() -> new GoldSample(
                date,
                date,
                ForecastHorizon.NEXT_DAY,
                features(),
                ForecastDirection.NEUTRAL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标日期");
    }

    @Test
    void copiesDatasetContent() {
        List<GoldSample> samples = new java.util.ArrayList<>();
        GoldDataset dataset = new GoldDataset(samples, 2);
        samples.add(new GoldSample(
                LocalDate.parse("2026-08-27"),
                LocalDate.parse("2026-08-28"),
                ForecastHorizon.NEXT_DAY,
                features(),
                ForecastDirection.BULLISH
        ));

        assertThat(dataset.samples()).isEmpty();
        assertThat(dataset.skippedCount()).isEqualTo(2);
    }

    private GoldFeatures features() {
        Map<String, Double> values = new LinkedHashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        return new GoldFeatures(values);
    }
}
