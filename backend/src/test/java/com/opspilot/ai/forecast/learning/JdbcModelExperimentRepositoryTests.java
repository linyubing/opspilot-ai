package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class JdbcModelExperimentRepositoryTests {

    @Autowired
    private JdbcModelExperimentRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsAndFindsExperiment() {
        ModelExperiment experiment = createSampleExperiment();
        repo.create(experiment);

        var found = repo.findById(experiment.id());
        assertThat(found).isPresent();
        assertThat(found.get().horizon()).isEqualTo("FIVE_DAYS");
        assertThat(found.get().status()).isEqualTo(ModelExperimentStatus.CREATED);
    }

    @Test
    void marksExperimentRunning() {
        ModelExperiment experiment = createSampleExperiment();
        repo.create(experiment);

        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        repo.markRunning(experiment.id(), startedAt);

        var found = repo.findById(experiment.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ModelExperimentStatus.RUNNING);
        assertThat(found.get().startedAt()).isNotNull();
    }

    @Test
    void completesExperimentWithMetrics() {
        ModelExperiment experiment = createSampleExperiment();
        repo.create(experiment);

        ModelExperiment completed = new ModelExperiment(
                experiment.id(),
                experiment.horizon(),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.splitVersion(),
                experiment.datasetHash(),
                experiment.parameters(),
                experiment.dataStart(),
                experiment.dataEnd(),
                experiment.trainStart(),
                experiment.validationStart(),
                experiment.validationEnd(),
                experiment.holdoutStart(),
                experiment.holdoutEnd(),
                experiment.validationSamples(),
                experiment.holdoutSamples(),
                ModelExperimentStatus.COMPLETED,
                experiment.gitCommit(),
                null,
                experiment.createdAt(),
                experiment.startedAt(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        ModelExperimentMetric majorityMetric = createMetric(experiment.id(), ModelType.MAJORITY);
        ModelExperimentMetric logisticMetric = createMetric(experiment.id(), ModelType.LOGISTIC);

        repo.complete(experiment.id(), completed, List.of(majorityMetric, logisticMetric));

        var found = repo.findById(experiment.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ModelExperimentStatus.COMPLETED);

        var metrics = repo.findMetrics(experiment.id());
        assertThat(metrics).hasSize(2);
        assertThat(metrics).extracting(m -> m.modelType())
                .containsExactlyInAnyOrder(ModelType.MAJORITY, ModelType.LOGISTIC);
    }

    @Test
    void failsExperiment() {
        ModelExperiment experiment = createSampleExperiment();
        repo.create(experiment);

        String message = "模型训练失败：数据不足";
        repo.fail(experiment.id(), message, OffsetDateTime.now(ZoneOffset.UTC));

        var found = repo.findById(experiment.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(ModelExperimentStatus.FAILED);
        assertThat(found.get().failureMessage()).isEqualTo(message);
    }

    @Test
    void findRecentReturnsNewestFirst() {
        ModelExperiment exp1 = createSampleExperiment();
        ModelExperiment exp2 = createSampleExperiment();

        repo.create(exp1);
        repo.create(exp2);

        var recent = repo.findRecent(10);
        assertThat(recent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(recent.get(0).createdAt()).isAfterOrEqualTo(recent.get(1).createdAt());
    }

    @Test
    void completesWithTransactionRollback() {
        ModelExperiment experiment = createSampleExperiment();
        repo.create(experiment);

        ModelExperiment completed = new ModelExperiment(
                experiment.id(),
                experiment.horizon(),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.splitVersion(),
                experiment.datasetHash(),
                experiment.parameters(),
                experiment.dataStart(),
                experiment.dataEnd(),
                experiment.trainStart(),
                experiment.validationStart(),
                experiment.validationEnd(),
                experiment.holdoutStart(),
                experiment.holdoutEnd(),
                experiment.validationSamples(),
                experiment.holdoutSamples(),
                ModelExperimentStatus.COMPLETED,
                experiment.gitCommit(),
                null,
                experiment.createdAt(),
                experiment.startedAt(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        ModelExperimentMetric validMetric = createMetric(experiment.id(), ModelType.MAJORITY);
        ModelExperimentMetric duplicateMetric = createMetric(experiment.id(), ModelType.MAJORITY);

        assertThatThrownBy(() -> repo.complete(experiment.id(), completed, List.of(validMetric, duplicateMetric)))
                .isInstanceOf(Exception.class);

        var found = repo.findById(experiment.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isNotEqualTo(ModelExperimentStatus.COMPLETED);
        assertThat(found.get().completedAt()).isNull();

        var metrics = repo.findMetrics(experiment.id());
        assertThat(metrics).isEmpty();
    }

    private ModelExperiment createSampleExperiment() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return new ModelExperiment(
                id,
                "FIVE_DAYS",
                "gold-features-v2",
                "gold-label-v1",
                "gold-temporal-split-v1",
                "abc123def456",
                Map.of("horizon", "FIVE_DAYS"),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                LocalDate.of(2025, 1, 10),
                LocalDate.of(2025, 12, 31),
                240,
                240,
                ModelExperimentStatus.CREATED,
                "unknown",
                null,
                now,
                null,
                null
        );
    }

    private ModelExperimentMetric createMetric(UUID experimentId, ModelType modelType) {
        Map<String, Object> recalls = new LinkedHashMap<>();
        recalls.put("BULLISH", new BigDecimal("0.6"));
        recalls.put("NEUTRAL", new BigDecimal("0.5"));
        recalls.put("BEARISH", new BigDecimal("0.7"));

        Map<String, Map<String, Integer>> confusionMatrix = new LinkedHashMap<>();
        confusionMatrix.put("BULLISH", Map.of("BULLISH", 100, "NEUTRAL", 20, "BEARISH", 10));
        confusionMatrix.put("NEUTRAL", Map.of("BULLISH", 15, "NEUTRAL", 80, "BEARISH", 15));
        confusionMatrix.put("BEARISH", Map.of("BULLISH", 10, "NEUTRAL", 15, "BEARISH", 115));

        return new ModelExperimentMetric(
                experimentId,
                modelType,
                240,
                200,
                new BigDecimal("0.8333"),
                new BigDecimal("0.6000"),
                new BigDecimal("0.6000"),
                new BigDecimal("0.4500"),
                new BigDecimal("1.2000"),
                recalls,
                confusionMatrix
        );
    }
}
