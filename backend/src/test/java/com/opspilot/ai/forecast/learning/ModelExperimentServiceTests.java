package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentServiceTests {

    private GoldDatasetBuilder datasetBuilder;
    private WalkForwardService walkForward;
    private GoldDatasetFingerprint fingerprint;
    private ModelExperimentRepository repo;
    private ModelExperimentProperties properties;
    private TemporalSplitter splitter;
    private Clock clock;
    private ModelExperimentService service;

    @BeforeEach
    void setUp() {
        datasetBuilder = mock(GoldDatasetBuilder.class);
        walkForward = mock(WalkForwardService.class);
        fingerprint = mock(GoldDatasetFingerprint.class);
        repo = mock(ModelExperimentRepository.class);
        properties = mock(ModelExperimentProperties.class);
        splitter = mock(TemporalSplitter.class);
        clock = Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);
        service = new ModelExperimentService(
                datasetBuilder, walkForward, fingerprint, repo,
                properties, null, splitter, clock
        );
    }

    @Test
    void buildsDatasetOnceAndSplitsOnce() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        ModelExperimentResult result = service.run(horizon);

        verify(datasetBuilder, org.mockito.Mockito.times(1)).build(horizon);
        verify(splitter, org.mockito.Mockito.times(1)).split(dataset.samples(), horizon);
        verify(walkForward, org.mockito.Mockito.times(1)).run(split, horizon, FeatureProfile.ALL_36);
        assertThat(result.experiment().status()).isEqualTo(ModelExperimentStatus.COMPLETED);
    }

    @Test
    void passesProfileToWalkForward() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.BASE_16)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        ModelExperimentResult result = service.run(horizon, FeatureProfile.BASE_16);

        verify(walkForward).run(split, horizon, FeatureProfile.BASE_16);
        assertThat(result.experiment().featureProfile()).isEqualTo(FeatureProfile.BASE_16);
    }

    @Test
    void fingerprintIsStableAndMatchesActualData() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123def456");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        ModelExperimentResult result = service.run(horizon);

        assertThat(result.experiment().datasetHash()).isEqualTo("abc123def456");
    }

    @Test
    void intervalsAndSampleCountsComeFromActualSplit() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        ModelExperimentResult result = service.run(horizon);

        assertThat(result.experiment().trainStart()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(result.experiment().validationStart()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(result.experiment().validationEnd()).isEqualTo(split.validation().getLast().asOfDate());
        assertThat(result.experiment().holdoutStart()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(result.experiment().holdoutEnd()).isEqualTo(split.finalHoldout().getLast().asOfDate());
        assertThat(result.experiment().validationSamples()).isEqualTo(240);
        assertThat(result.experiment().holdoutSamples()).isEqualTo(240);
    }

    @Test
    void savesBothModelMetrics() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        ModelExperimentResult result = service.run(horizon);

        assertThat(result.majority()).isNotNull();
        assertThat(result.logistic()).isNotNull();
        assertThat(result.majority().modelType()).isEqualTo(ModelType.MAJORITY);
        assertThat(result.logistic().modelType()).isEqualTo(ModelType.LOGISTIC);
    }

    @Test
    void compareRunsThreeProfiles() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.BASE_16)).thenReturn(createReport());
        when(walkForward.run(split, horizon, FeatureProfile.OHLC_20)).thenReturn(createReport());
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        List<ModelExperimentResult> results = service.compare(horizon);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).experiment().featureProfile()).isEqualTo(FeatureProfile.BASE_16);
        assertThat(results.get(1).experiment().featureProfile()).isEqualTo(FeatureProfile.OHLC_20);
        assertThat(results.get(2).experiment().featureProfile()).isEqualTo(FeatureProfile.ALL_36);
        verify(datasetBuilder, org.mockito.Mockito.times(1)).build(horizon);
        verify(splitter, org.mockito.Mockito.times(1)).split(dataset.samples(), horizon);
    }

    @Test
    void compareExperimentsShareSameHashAndDates() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("sameHash123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.BASE_16)).thenReturn(createReport());
        when(walkForward.run(split, horizon, FeatureProfile.OHLC_20)).thenReturn(createReport());
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36)).thenReturn(createReport());
        when(properties.gitCommit()).thenReturn("unknown");

        List<ModelExperimentResult> results = service.compare(horizon);

        String hash = results.get(0).experiment().datasetHash();
        LocalDate dataStart = results.get(0).experiment().dataStart();
        LocalDate validStart = results.get(0).experiment().validationStart();
        for (ModelExperimentResult r : results) {
            assertThat(r.experiment().datasetHash()).isEqualTo(hash);
            assertThat(r.experiment().dataStart()).isEqualTo(dataStart);
            assertThat(r.experiment().validationStart()).isEqualTo(validStart);
        }
    }

    @Test
    void failedExperimentHasCorrectStatusAndMessage() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36))
                .thenThrow(new RuntimeException("训练失败"));
        when(properties.gitCommit()).thenReturn("unknown");

        assertThatThrownBy(() -> service.run(horizon))
                .isInstanceOf(ModelExperimentException.class)
                .hasMessageContaining("训练失败");

        verify(repo).fail(any(UUID.class), eq("训练失败"), any());
    }

    @Test
    void missingRecallStaysNull() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36))
                .thenReturn(createReportWithNullRecall());
        when(properties.gitCommit()).thenReturn("unknown");

        ModelExperimentResult result = service.run(horizon);

        assertThat(result.majority().recalls().get(ForecastDirection.BULLISH)).isNull();
        assertThat(result.logistic().recalls().get(ForecastDirection.NEUTRAL)).isNull();
    }

    @Test
    void findByIdThrowsForNonExistent() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(ModelExperimentNotFoundException.class)
                .hasMessageContaining("模型实验不存在");
    }

    @Test
    void failedExperimentTruncatesLongMessage() {
        ForecastHorizon horizon = ForecastHorizon.NEXT_DAY;
        GoldDataset dataset = createSampleDataset();
        TemporalDataset split = createSampleSplit();
        String longMessage = "x".repeat(1500);

        when(datasetBuilder.build(horizon)).thenReturn(dataset);
        when(fingerprint.hash(dataset)).thenReturn("abc123");
        when(splitter.split(dataset.samples(), horizon)).thenReturn(split);
        when(walkForward.run(split, horizon, FeatureProfile.ALL_36))
                .thenThrow(new RuntimeException(longMessage));
        when(properties.gitCommit()).thenReturn("unknown");

        assertThatThrownBy(() -> service.run(horizon))
                .isInstanceOf(ModelExperimentException.class);

        verify(repo).fail(any(UUID.class), eq(longMessage.substring(0, 1000)), any());
    }

    private GoldDataset createSampleDataset() {
        List<GoldSample> samples = List.of(
                new GoldSample(
                        LocalDate.of(2020, 1, 1),
                        LocalDate.of(2020, 1, 2),
                        ForecastHorizon.NEXT_DAY,
                        createFeatures(),
                        ForecastDirection.BULLISH
                )
        );
        return new GoldDataset(samples, 0);
    }

    private TemporalDataset createSampleSplit() {
        List<GoldSample> training = new java.util.ArrayList<>();
        training.add(new GoldSample(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                        ForecastHorizon.NEXT_DAY, createFeatures(), ForecastDirection.BULLISH));

        List<GoldSample> validation = new java.util.ArrayList<>();
        for (int i = 0; i < 240; i++) {
            validation.add(new GoldSample(
                    LocalDate.of(2024, 1, 1).plusDays(i),
                    LocalDate.of(2024, 1, 2).plusDays(i),
                    ForecastHorizon.NEXT_DAY,
                    createFeatures(),
                    ForecastDirection.BULLISH
            ));
        }

        List<GoldSample> holdout = new java.util.ArrayList<>();
        for (int i = 0; i < 240; i++) {
            holdout.add(new GoldSample(
                    LocalDate.of(2025, 1, 10).plusDays(i),
                    LocalDate.of(2025, 1, 11).plusDays(i),
                    ForecastHorizon.NEXT_DAY,
                    createFeatures(),
                    ForecastDirection.BULLISH
            ));
        }
        return new TemporalDataset(training, validation, holdout);
    }

    private GoldFeatures createFeatures() {
        Map<String, Double> values = new java.util.HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.5));
        return new GoldFeatures(values);
    }

    private WalkForwardReport createReport() {
        ForecastMetrics majority = createMetrics();
        ForecastMetrics logistic = createMetrics();
        return new WalkForwardReport(
                ForecastHorizon.NEXT_DAY,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                240,
                20,
                12,
                majority,
                logistic,
                240,
                LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 12, 31)
        );
    }

    private WalkForwardReport createReportWithNullRecall() {
        Map<ForecastDirection, BigDecimal> recalls = new EnumMap<>(ForecastDirection.class);
        recalls.put(ForecastDirection.BULLISH, null);
        recalls.put(ForecastDirection.NEUTRAL, null);
        recalls.put(ForecastDirection.BEARISH, null);

        Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix = new EnumMap<>(ForecastDirection.class);
        matrix.put(ForecastDirection.BULLISH, Map.of(
                ForecastDirection.BULLISH, 100, ForecastDirection.NEUTRAL, 20, ForecastDirection.BEARISH, 10));
        matrix.put(ForecastDirection.NEUTRAL, Map.of(
                ForecastDirection.BULLISH, 15, ForecastDirection.NEUTRAL, 80, ForecastDirection.BEARISH, 15));
        matrix.put(ForecastDirection.BEARISH, Map.of(
                ForecastDirection.BULLISH, 10, ForecastDirection.NEUTRAL, 15, ForecastDirection.BEARISH, 115));

        ForecastMetrics majority = new ForecastMetrics(
                240, 200, new BigDecimal("0.8333"), new BigDecimal("0.6000"),
                new BigDecimal("0.6000"), new BigDecimal("0.4500"), new BigDecimal("1.2000"),
                recalls, matrix, true
        );
        ForecastMetrics logistic = new ForecastMetrics(
                240, 200, new BigDecimal("0.8333"), new BigDecimal("0.6000"),
                new BigDecimal("0.6000"), new BigDecimal("0.4500"), new BigDecimal("1.2000"),
                recalls, matrix, true
        );
        return new WalkForwardReport(
                ForecastHorizon.NEXT_DAY,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                240, 20, 12, majority, logistic, 240,
                LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 12, 31)
        );
    }

    private ForecastMetrics createMetrics() {
        Map<ForecastDirection, BigDecimal> recalls = new EnumMap<>(ForecastDirection.class);
        recalls.put(ForecastDirection.BULLISH, new BigDecimal("0.6"));
        recalls.put(ForecastDirection.NEUTRAL, new BigDecimal("0.5"));
        recalls.put(ForecastDirection.BEARISH, new BigDecimal("0.7"));

        Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix = new EnumMap<>(ForecastDirection.class);
        matrix.put(ForecastDirection.BULLISH, Map.of(
                ForecastDirection.BULLISH, 100, ForecastDirection.NEUTRAL, 20, ForecastDirection.BEARISH, 10));
        matrix.put(ForecastDirection.NEUTRAL, Map.of(
                ForecastDirection.BULLISH, 15, ForecastDirection.NEUTRAL, 80, ForecastDirection.BEARISH, 15));
        matrix.put(ForecastDirection.BEARISH, Map.of(
                ForecastDirection.BULLISH, 10, ForecastDirection.NEUTRAL, 15, ForecastDirection.BEARISH, 115));

        return new ForecastMetrics(
                240, 200, new BigDecimal("0.8333"), new BigDecimal("0.6000"),
                new BigDecimal("0.6000"), new BigDecimal("0.4500"), new BigDecimal("1.2000"),
                recalls, matrix, true
        );
    }
}
