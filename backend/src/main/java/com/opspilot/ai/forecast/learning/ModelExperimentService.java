package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 管理黄金模型实验的创建、执行和查询。 */
@Service
public class ModelExperimentService {

    private static final int REFIT_EVERY = 20;
    private static final double CONFIDENCE_THRESHOLD = 0.55;

    private final GoldDatasetBuilder datasetBuilder;
    private final WalkForwardService walkForward;
    private final GoldDatasetFingerprint fingerprint;
    private final ModelExperimentRepository repo;
    private final ModelExperimentProperties properties;
    private final ObjectMapper objectMapper;

    public ModelExperimentService(
            GoldDatasetBuilder datasetBuilder,
            WalkForwardService walkForward,
            GoldDatasetFingerprint fingerprint,
            ModelExperimentRepository repo,
            ModelExperimentProperties properties,
            ObjectMapper objectMapper
    ) {
        this.datasetBuilder = datasetBuilder;
        this.walkForward = walkForward;
        this.fingerprint = fingerprint;
        this.repo = repo;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ModelExperiment run(ForecastHorizon horizon) {
        UUID experimentId = UUID.randomUUID();
        OffsetDateTime now = now();

        GoldDataset dataset = datasetBuilder.build(horizon);
        String datasetHash = fingerprint.hash(dataset);

        ModelExperiment experiment = new ModelExperiment(
                experimentId,
                horizon.name(),
                GoldFeatures.VERSION,
                GoldForecastRule.RULE_VERSION,
                TemporalSplitter.VERSION,
                datasetHash,
                buildParameters(horizon),
                dataset.samples().getFirst().asOfDate(),
                dataset.samples().getLast().asOfDate(),
                dataset.samples().getFirst().asOfDate(),
                dataset.samples().getFirst().asOfDate(),
                dataset.samples().getLast().asOfDate(),
                dataset.samples().getFirst().asOfDate(),
                dataset.samples().getLast().asOfDate(),
                0,
                0,
                ModelExperimentStatus.CREATED,
                properties.gitCommit(),
                null,
                now,
                null,
                null
        );

        repo.create(experiment);
        repo.markRunning(experimentId, now);

        try {
            WalkForwardReport report = walkForward.run(dataset, horizon);

            TemporalDataset split = new TemporalSplitter().split(dataset.samples(), horizon);

            ModelExperimentMetric majorityMetric = toMetric(
                    experimentId, ModelType.MAJORITY, report.majority()
            );
            ModelExperimentMetric logisticMetric = toMetric(
                    experimentId, ModelType.LOGISTIC, report.logistic()
            );

            ModelExperiment completed = new ModelExperiment(
                    experimentId,
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
                    split.finalHoldout().getFirst().asOfDate(),
                    split.finalHoldout().getLast().asOfDate(),
                    split.validation().size(),
                    split.finalHoldout().size(),
                    ModelExperimentStatus.COMPLETED,
                    experiment.gitCommit(),
                    null,
                    experiment.createdAt(),
                    experiment.startedAt(),
                    now()
            );

            repo.complete(experimentId, completed, List.of(majorityMetric, logisticMetric));
            return completed;
        } catch (Exception e) {
            String message = safeMessage(e);
            repo.fail(experimentId, message, now());
            throw new ModelExperimentException("模型实验执行失败: " + message, e);
        }
    }

    public ModelExperiment findById(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new ModelExperimentNotFoundException(
                        "模型实验不存在，编号=" + id
                ));
    }

    public List<ModelExperiment> findRecent(int limit) {
        return repo.findRecent(limit);
    }

    public List<ModelExperimentMetric> findMetrics(UUID experimentId) {
        return repo.findMetrics(experimentId);
    }

    private ModelExperimentMetric toMetric(
            UUID experimentId,
            ModelType modelType,
            ForecastMetrics metrics
    ) {
        return new ModelExperimentMetric(
                experimentId,
                modelType,
                metrics.sampleCount(),
                metrics.coveredCount(),
                metrics.coverage(),
                metrics.accuracy(),
                metrics.balancedAccuracy(),
                metrics.brierScore(),
                metrics.logLoss(),
                toRecallsMap(metrics.recalls()),
                toConfusionMatrixMap(metrics.confusionMatrix())
        );
    }

    private Map<String, Object> toRecallsMap(Map<ForecastDirection, BigDecimal> recalls) {
        return recalls.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> e.getValue() == null ? BigDecimal.ZERO : e.getValue()
                ));
    }

    private Map<String, Map<String, Integer>> toConfusionMatrixMap(
            Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix
    ) {
        return matrix.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().name(),
                        e -> e.getValue().entrySet().stream()
                                .collect(Collectors.toMap(
                                        inner -> inner.getKey().name(),
                                        Map.Entry::getValue
                                ))
                ));
    }

    private Map<String, Object> buildParameters(ForecastHorizon horizon) {
        return Map.of(
                "horizon", horizon.name(),
                "refitEvery", REFIT_EVERY,
                "confidenceThreshold", CONFIDENCE_THRESHOLD,
                "majorityTrainer", "MajorityGoldTrainer",
                "logisticTrainer", "TribuoGoldTrainer",
                "featureCount", GoldFeatures.NAMES.size(),
                "featureVersion", GoldFeatures.VERSION,
                "labelVersion", GoldForecastRule.RULE_VERSION,
                "splitVersion", TemporalSplitter.VERSION
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
