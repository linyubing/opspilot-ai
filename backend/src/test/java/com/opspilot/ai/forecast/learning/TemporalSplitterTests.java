package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.backtest.BacktestDataInsufficientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalSplitterTests {

    private final TemporalSplitter splitter = new TemporalSplitter();

    @Test
    @DisplayName("切分训练区间、开发验证区间和最终留出区间")
    void splitsNonOverlappingRanges() {
        TemporalDataset result = splitter.split(
                samples(1_000, ForecastHorizon.NEXT_DAY),
                ForecastHorizon.NEXT_DAY
        );

        assertThat(result.finalHoldout()).hasSize(240);
        assertThat(result.validation()).hasSize(240);
        assertThat(result.training()).hasSize(518);
        assertThat(result.training().getLast().targetDate())
                .isBefore(result.validation().getFirst().asOfDate());
        assertThat(result.validation().getLast().targetDate())
                .isBefore(result.finalHoldout().getFirst().asOfDate());
    }

    @Test
    @DisplayName("二十日预测在各区间之间保留二十条隔离样本")
    void keepsHorizonGaps() {
        TemporalDataset result = splitter.split(
                samples(1_100, ForecastHorizon.TWENTY_DAYS),
                ForecastHorizon.TWENTY_DAYS
        );

        assertThat(result.training()).hasSize(580);
        assertThat(result.validation()).hasSize(240);
        assertThat(result.finalHoldout()).hasSize(240);
        assertThat(result.training().getLast().targetDate())
                .isBefore(result.validation().getFirst().asOfDate());
        assertThat(result.validation().getLast().targetDate())
                .isBefore(result.finalHoldout().getFirst().asOfDate());
    }

    @Test
    @DisplayName("完整样本不足时拒绝切分")
    void rejectsInsufficientSamples() {
        int required = 240 + 240 + 500 + 2 * 20;

        assertThatThrownBy(() -> splitter.split(
                samples(required - 1, ForecastHorizon.TWENTY_DAYS),
                ForecastHorizon.TWENTY_DAYS
        ))
                .isInstanceOf(BacktestDataInsufficientException.class)
                .hasMessageContaining("实际数量=" + (required - 1))
                .hasMessageContaining("所需数量=" + required);
    }

    private List<GoldSample> samples(
            int count,
            ForecastHorizon horizon
    ) {
        LocalDate start = LocalDate.parse("2020-01-01");
        GoldFeatures features = features();
        return IntStream.range(0, count)
                .mapToObj(index -> new GoldSample(
                        start.plusDays(index),
                        start.plusDays(index + horizon.sessions()),
                        horizon,
                        features,
                        ForecastDirection.NEUTRAL
                ))
                .toList();
    }

    private GoldFeatures features() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        return new GoldFeatures(values);
    }
}
