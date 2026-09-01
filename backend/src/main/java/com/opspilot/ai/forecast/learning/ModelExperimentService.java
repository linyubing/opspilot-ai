package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    private final TemporalSplitter splitter;
    private final Clock clock;

    public ModelExperimentService(
            GoldDatasetBuilder datasetBuilder,
            WalkForwardService walkForward,
            GoldDatasetFingerprint fingerprint,
            ModelExperimentRepository repo,
            ModelExperimentProperties properties,
            ObjectMapper objectMapper,
            TemporalSplitter splitter,
            Clock clock
    ) {
        this.datasetBuilder = datasetBuilder;
        this.walkForward = walkForward;
        this.fingerprint = fingerprint;
        this.repo = repo;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.splitter = splitter;
        this.clock = clock;
    }

    public ModelExperimentResult run(ForecastHorizon horizon) {
        return run(horizon, FeatureProfile.ALL_36);
    }

    public ModelExperimentResult run(ForecastHorizon horizon, FeatureProfile profile) {
        UUID experimentId = UUID.randomUUID();
        OffsetDateTime now = now();

        GoldDataset dataset = datasetBuilder.build(horizon);
        String datasetHash = fingerprint.hash(dataset);

        TemporalDataset split = splitter.split(dataset.samples(), horizon);
        TemporalDataset filtered = filterFeatures(split, profile.featureNames());

        ModelExperiment experiment = new ModelExperiment(
                experimentId,
                horizon.name(),
                GoldFeatures.VERSION,
                GoldForecastRule.RULE_VERSION,
                TemporalSplitter.VERSION,
                datasetHash,
                profile.name(),
                buildParameters(horizon, profile),
                dataset.samples().getFirst().asOfDate(),
                dataset.samples().getLast().asOfDate(),
                split.training().getFirst().asOfDate(),
                split.validation().getFirst().asOfDate(),
                split.validation().getLast().asOfDate(),
                split.finalHoldout().getFirst().asOfDate(),
                split.finalHoldout().getLast().asOfDate(),
                split.validation().size(),
                split.finalHoldout().size(),
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
            WalkForwardReport report = walkForward.run(filtered, horizon);

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
                    experiment.featureProfile(),
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
                    now()
            );

            repo.complete(experimentId, completed, List.of(majorityMetric, logisticMetric));
            return new ModelExperimentResult(completed, majorityMetric, logisticMetric);
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
        Map<String, Object> result = new LinkedHashMap<>();
        for (ForecastDirection direction : ForecastDirection.values()) {
            result.put(direction.name(), recalls.get(direction));
        }
        return result;
    }

    private Map<String, Map<String, Integer>> toConfusionMatrixMap(
            Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix
    ) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<ForecastDirection, Map<ForecastDirection, Integer>> outer : matrix.entrySet()) {
            Map<String, Integer> inner = new LinkedHashMap<>();
            for (Map.Entry<ForecastDirection, Integer> entry : outer.getValue().entrySet()) {
                inner.put(entry.getKey().name(), entry.getValue());
            }
            result.put(outer.getKey().name(), inner);
        }
        return result;
    }

    private Map<String, Object> buildParameters(ForecastHorizon horizon, FeatureProfile profile) {
        return Map.of(
                "horizon", horizon.name(),
                "featureProfile", profile.name(),
                "featureCount", profile.featureNames().size(),
                "refitEvery", REFIT_EVERY,
                "confidenceThreshold", CONFIDENCE_THRESHOLD,
                "majorityTrainer", "MajorityGoldTrainer",
                "logisticTrainer", "TribuoGoldTrainer",
                "featureVersion", GoldFeatures.VERSION,
                "labelVersion", GoldForecastRule.RULE_VERSION,
                "splitVersion", TemporalSplitter.VERSION
        );
    }

    private TemporalDataset filterFeatures(TemporalDataset split, Set<String> allowedNames) {
        return new TemporalDataset(
                split.training().stream().map(s -> filterSample(s, allowedNames)).toList(),
                split.validation().stream().map(s -> filterSample(s, allowedNames)).toList(),
                split.finalHoldout().stream().map(s -> filterSample(s, allowedNames)).toList()
        );
    }

    private GoldSample filterSample(GoldSample sample, Set<String> allowedNames) {
        Map<String, Double> filtered = new LinkedHashMap<>();
        for (String name : allowedNames) {
            Double value = sample.features().values().get(name);
            if (value != null) {
                filtered.put(name, value);
            }
        }
        return new GoldSample(
                sample.asOfDate(),
                sample.targetDate(),
                sample.horizon(),
                new GoldFeatures(filtered),
                sample.label()
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
