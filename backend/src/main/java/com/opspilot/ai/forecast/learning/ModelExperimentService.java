package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final GitCommitProvider gitCommitProvider;
    private final XgboostProperties xgboostProperties;
    private final ObjectMapper objectMapper;
    private final TemporalSplitter splitter;
    private final Stage8CandidateEvaluator candidateEvaluator;
    private final Clock clock;

    public ModelExperimentService(
            GoldDatasetBuilder datasetBuilder,
            WalkForwardService walkForward,
            GoldDatasetFingerprint fingerprint,
            ModelExperimentRepository repo,
            GitCommitProvider gitCommitProvider,
            XgboostProperties xgboostProperties,
            ObjectMapper objectMapper,
            TemporalSplitter splitter,
            Stage8CandidateEvaluator candidateEvaluator,
            Clock clock
    ) {
        this.datasetBuilder = datasetBuilder;
        this.walkForward = walkForward;
        this.fingerprint = fingerprint;
        this.repo = repo;
        this.gitCommitProvider = gitCommitProvider;
        this.xgboostProperties = xgboostProperties;
        this.objectMapper = objectMapper;
        this.splitter = splitter;
        this.candidateEvaluator = candidateEvaluator;
        this.clock = clock;
    }

    public ModelExperimentResult run(ForecastHorizon horizon) {
        return run(horizon, FeatureProfile.ALL_36);
    }

    public ModelExperimentResult run(ForecastHorizon horizon, FeatureProfile profile) {
        String gitCommit = gitCommitProvider.getRequired();
        UUID experimentId = UUID.randomUUID();
        OffsetDateTime now = now();

        GoldDataset dataset = datasetBuilder.build(horizon);
        String datasetHash = fingerprint.hash(dataset);
        TemporalDataset split = splitter.split(dataset.samples(), horizon);

        return runSingleExperiment(experimentId, null, now, horizon, profile, datasetHash, dataset, split, gitCommit);
    }

    public ModelComparisonResult compare(ForecastHorizon horizon) {
        String gitCommit = gitCommitProvider.getRequired();
        UUID comparisonId = UUID.randomUUID();
        OffsetDateTime now = now();

        GoldDataset dataset = datasetBuilder.build(horizon);
        String datasetHash = fingerprint.hash(dataset);
        TemporalDataset split = splitter.split(dataset.samples(), horizon);

        List<ModelExperiment> experiments = new ArrayList<>();
        for (FeatureProfile profile : FeatureProfile.values()) {
            experiments.add(new ModelExperiment(
                    UUID.randomUUID(),
                    comparisonId,
                    horizon.name(),
                    GoldFeatures.VERSION,
                    GoldForecastRule.RULE_VERSION,
                    TemporalSplitter.VERSION,
                    datasetHash,
                    profile,
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
                    gitCommit,
                    null,
                    now,
                    now,
                    null
            ));
        }

        repo.createComparison(experiments);
        repo.markComparisonRunning(comparisonId, now);

        List<ModelExperimentResult> results = new ArrayList<>();
        try {
            for (int i = 0; i < experiments.size(); i++) {
                ModelExperiment experiment = experiments.get(i);
                FeatureProfile profile = experiment.featureProfile();

                WalkForwardReport report = walkForward.run(split, horizon, profile);

                Map<ModelType, ModelExperimentMetric> persistedMetrics = new EnumMap<>(ModelType.class);
                List<ModelExperimentMetric> metricList = new ArrayList<>();
                for (ModelType type : ModelType.values()) {
                    ModelExperimentMetric metric = toMetric(experiment.id(), type, report.metric(type));
                    persistedMetrics.put(type, metric);
                    metricList.add(metric);
                }

                ModelExperiment completed = new ModelExperiment(
                        experiment.id(),
                        experiment.comparisonId(),
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

                results.add(new ModelExperimentResult(completed, persistedMetrics));
            }

            List<ModelExperimentMetric> allMetrics = new ArrayList<>();
            for (ModelExperimentResult result : results) {
                allMetrics.addAll(result.metrics().values());
            }
            Stage8Candidate candidate = candidateEvaluator.evaluate(results);
            List<ModelExperiment> completedExperiments = results.stream()
                    .map(ModelExperimentResult::experiment)
                    .toList();
            repo.completeComparison(comparisonId, completedExperiments, allMetrics);
            return new ModelComparisonResult(comparisonId, horizon, results, candidate);
        } catch (ModelUnavailableException e) {
            String message = safeMessage(e);
            repo.failComparison(comparisonId, message, now());
            throw e;
        } catch (Exception e) {
            String message = safeMessage(e);
            repo.failComparison(comparisonId, message, now());
            throw new ModelExperimentException("模型实验执行失败: " + message, e);
        }
    }

    private ModelExperimentResult runSingleExperiment(
            UUID experimentId,
            UUID comparisonId,
            OffsetDateTime now,
            ForecastHorizon horizon,
            FeatureProfile profile,
            String datasetHash,
            GoldDataset dataset,
            TemporalDataset split,
            String gitCommit
    ) {
        ModelExperiment experiment = new ModelExperiment(
                experimentId,
                comparisonId,
                horizon.name(),
                GoldFeatures.VERSION,
                GoldForecastRule.RULE_VERSION,
                TemporalSplitter.VERSION,
                datasetHash,
                profile,
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
                gitCommit,
                null,
                now,
                now,
                null
        );

        repo.create(experiment);
        repo.markRunning(experimentId, now);

        try {
            WalkForwardReport report = walkForward.run(split, horizon, profile);

            Map<ModelType, ModelExperimentMetric> persistedMetrics = new EnumMap<>(ModelType.class);
            List<ModelExperimentMetric> metricList = new ArrayList<>();
            for (ModelType type : ModelType.values()) {
                ModelExperimentMetric metric = toMetric(experimentId, type, report.metric(type));
                persistedMetrics.put(type, metric);
                metricList.add(metric);
            }

            ModelExperiment completed = new ModelExperiment(
                    experimentId,
                    experiment.comparisonId(),
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

            repo.complete(experimentId, completed, metricList);
            return new ModelExperimentResult(completed, persistedMetrics);
        } catch (ModelUnavailableException e) {
            String message = safeMessage(e);
            repo.fail(experimentId, message, now());
            throw e;
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("horizon", horizon.name());
        params.put("featureProfile", profile.name());
        params.put("featureCount", profile.featureNames().size());
        params.put("refitEvery", REFIT_EVERY);
        params.put("confidenceThreshold", CONFIDENCE_THRESHOLD);
        params.put("majorityTrainer", "MajorityGoldTrainer");
        params.put("logisticTrainer", "TribuoGoldTrainer");
        params.put("featureVersion", GoldFeatures.VERSION);
        params.put("labelVersion", GoldForecastRule.RULE_VERSION);
        params.put("splitVersion", TemporalSplitter.VERSION);
        Map<String, Object> xgboostParams = new LinkedHashMap<>();
        xgboostParams.put("name", "tribuo-xgboost");
        xgboostParams.put("numTrees", xgboostProperties.numTrees());
        xgboostParams.put("eta", xgboostProperties.eta());
        xgboostParams.put("gamma", xgboostProperties.gamma());
        xgboostParams.put("maxDepth", xgboostProperties.maxDepth());
        xgboostParams.put("minChildWeight", xgboostProperties.minChildWeight());
        xgboostParams.put("subsample", xgboostProperties.subsample());
        xgboostParams.put("featureSubsample", xgboostProperties.featureSubsample());
        xgboostParams.put("lambda", xgboostProperties.lambda());
        xgboostParams.put("alpha", xgboostProperties.alpha());
        xgboostParams.put("nThread", xgboostProperties.nThread());
        xgboostParams.put("seed", xgboostProperties.seed());
        params.put("xgboostTrainer", xgboostParams);
        return params;
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
