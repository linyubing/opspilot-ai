package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalkForwardServiceTests {

    private GoldDatasetBuilder builder;
    private TemporalSplitter splitter;
    private RecordingTrainer majority;
    private RecordingTrainer logistic;
    private WalkForwardService service;
    private TemporalDataset dataset;

    @BeforeEach
    void setUp() {
        builder = mock(GoldDatasetBuilder.class);
        splitter = mock(TemporalSplitter.class);
        majority = new RecordingTrainer(
                "majority-v1",
                new DirectionProbabilities(0.1, 0.8, 0.1)
        );
        logistic = new RecordingTrainer(
                "logistic-v1",
                new DirectionProbabilities(0.8, 0.1, 0.1)
        );
        service = new WalkForwardService(
                builder,
                splitter,
                majority,
                logistic,
                new ForecastEvaluator()
        );
        dataset = dataset();
        GoldDataset raw = new GoldDataset(List.of(), 0);
        when(builder.build(ForecastHorizon.NEXT_DAY)).thenReturn(raw);
        when(splitter.split(raw.samples(), ForecastHorizon.NEXT_DAY))
                .thenReturn(dataset);
    }

    @Test
    void refitsAfterEverySettledBlock() {
        WalkForwardReport report = service.run(ForecastHorizon.NEXT_DAY);

        assertThat(report.validationSamples()).isEqualTo(240);
        assertThat(report.refitCount()).isEqualTo(12);
        assertThat(majority.trainingSets).hasSize(12);
        assertThat(logistic.trainingSets).hasSize(12);
        for (int block = 0; block < 12; block++) {
            List<GoldSample> training = logistic.trainingSets.get(block);
            GoldSample firstScored = dataset.validation().get(block * 20);
            assertThat(training.getLast().targetDate())
                    .isBefore(firstScored.asOfDate());
            int settled = block == 0 ? 0 : block * 20 - 1;
            assertThat(training).hasSize(500 + settled);
        }
    }

    @Test
    void comparesModelsOnTheSameValidationSamples() {
        WalkForwardReport report = service.run(ForecastHorizon.NEXT_DAY);

        assertThat(report.majority().sampleCount()).isEqualTo(240);
        assertThat(report.logistic().sampleCount()).isEqualTo(240);
        assertThat(report.validationStart())
                .isEqualTo(dataset.validation().getFirst().asOfDate());
        assertThat(report.validationEnd())
                .isEqualTo(dataset.validation().getLast().asOfDate());
    }

    @Test
    void hidesFinalHoldoutLabelsAndFeatures() {
        WalkForwardReport report = service.run(ForecastHorizon.NEXT_DAY);

        assertThat(report.finalHoldoutSamples()).isEqualTo(240);
        assertThat(report.finalHoldoutStart())
                .isEqualTo(dataset.finalHoldout().getFirst().asOfDate());
        assertThat(report.finalHoldoutEnd())
                .isEqualTo(dataset.finalHoldout().getLast().asOfDate());
        assertThat(logistic.trainingSets)
                .allSatisfy(samples -> assertThat(samples)
                        .doesNotContainAnyElementsOf(dataset.finalHoldout()));
    }

    @Test
    void walkForwardDoesNotTrainOnUnresolvedLabels() {
        service.run(ForecastHorizon.NEXT_DAY);

        for (int block = 0; block < 12; block++) {
            List<GoldSample> training = logistic.trainingSets.get(block);
            GoldSample firstScored = dataset.validation().get(block * 20);
            assertThat(training.getLast().targetDate())
                    .isBefore(firstScored.asOfDate());
        }
    }

    private TemporalDataset dataset() {
        LocalDate start = LocalDate.parse("2020-01-01");
        List<GoldSample> training = samples(start, 0, 500);
        List<GoldSample> validation = samples(start, 501, 240);
        List<GoldSample> holdout = samples(start, 742, 240);
        return new TemporalDataset(training, validation, holdout);
    }

    private List<GoldSample> samples(
            LocalDate start,
            int offset,
            int count
    ) {
        List<GoldSample> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = offset + i;
            ForecastDirection label = ForecastDirection.values()[i % 3];
            result.add(new GoldSample(
                    start.plusDays(index),
                    start.plusDays(index + 1),
                    ForecastHorizon.NEXT_DAY,
                    features(),
                    label
            ));
        }
        return result;
    }

    private GoldFeatures features() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        return new GoldFeatures(values);
    }

    private static final class RecordingTrainer implements GoldTrainer {
        private final String name;
        private final DirectionProbabilities probabilities;
        private final List<List<GoldSample>> trainingSets = new ArrayList<>();

        private RecordingTrainer(
                String name,
                DirectionProbabilities probabilities
        ) {
            this.name = name;
            this.probabilities = probabilities;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public GoldClassifier train(List<GoldSample> samples, java.util.Set<String> featureNames) {
            trainingSets.add(List.copyOf(samples));
            return features -> probabilities;
        }
    }
}
