package com.opspilot.ai.forecast.learning;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在开发验证区间滚动重训并比较多数类、逻辑回归和XGBoost模型。 */
@Service
public class WalkForwardService {

    static final int REFIT_EVERY = 20;
    static final double CONFIDENCE = 0.55;

    private final GoldDatasetBuilder builder;
    private final TemporalSplitter splitter;
    private final GoldTrainer majorityTrainer;
    private final GoldTrainer logisticTrainer;
    private final GoldTrainer xgboostTrainer;
    private final ForecastEvaluator evaluator;
    private final ConfidencePolicy policy = new ConfidencePolicy(CONFIDENCE);

    public WalkForwardService(
            GoldDatasetBuilder builder,
            TemporalSplitter splitter,
            @Qualifier("majorityGoldTrainer") GoldTrainer majorityTrainer,
            @Qualifier("tribuoGoldTrainer") GoldTrainer logisticTrainer,
            @Qualifier("xgboostGoldTrainer") GoldTrainer xgboostTrainer,
            ForecastEvaluator evaluator
    ) {
        this.builder = builder;
        this.splitter = splitter;
        this.majorityTrainer = majorityTrainer;
        this.logisticTrainer = logisticTrainer;
        this.xgboostTrainer = xgboostTrainer;
        this.evaluator = evaluator;
    }

    public WalkForwardReport run(ForecastHorizon horizon) {
        return run(horizon, FeatureProfile.ALL_36);
    }

    public WalkForwardReport run(ForecastHorizon horizon, FeatureProfile profile) {
        GoldDataset raw = builder.build(horizon);
        TemporalDataset data = splitter.split(raw.samples(), horizon);
        return run(data, horizon, profile);
    }

    public WalkForwardReport run(GoldDataset dataset, ForecastHorizon horizon) {
        return run(dataset, horizon, FeatureProfile.ALL_36);
    }

    public WalkForwardReport run(GoldDataset dataset, ForecastHorizon horizon, FeatureProfile profile) {
        TemporalDataset data = splitter.split(dataset.samples(), horizon);
        return run(data, horizon, profile);
    }

    public WalkForwardReport run(TemporalDataset data, ForecastHorizon horizon) {
        return run(data, horizon, FeatureProfile.ALL_36);
    }

    public WalkForwardReport run(TemporalDataset data, ForecastHorizon horizon, FeatureProfile profile) {
        Map<ModelType, List<SettledPrediction>> predictions = new EnumMap<>(ModelType.class);
        predictions.put(ModelType.MAJORITY, predict(data, profile, majorityTrainer));
        predictions.put(ModelType.LOGISTIC, predict(data, profile, logisticTrainer));
        predictions.put(ModelType.XGBOOST, predict(data, profile, xgboostTrainer));
        int refits = (data.validation().size() + REFIT_EVERY - 1) / REFIT_EVERY;
        return report(horizon, data, predictions, refits);
    }

    /** 对照实验复用同一滚动流程，返回逐日结果，不接触最终留出集。 */
    public List<SettledPrediction> predict(TemporalDataset data, FeatureProfile profile, GoldTrainer trainer) {
        Set<String> featureNames = profile.featureNames();
        List<SettledPrediction> predictions = new ArrayList<>();
        List<GoldSample> validation = data.validation();

        for (int start = 0; start < validation.size(); start += REFIT_EVERY) {
            int end = Math.min(start + REFIT_EVERY, validation.size());
            List<GoldSample> block = validation.subList(start, end);
            List<GoldSample> training = trainingData(
                    data.training(),
                    validation.subList(0, start),
                    block.getFirst().asOfDate()
            );
            GoldClassifier classifier = trainer.train(training, featureNames);

            for (GoldSample sample : block) {
                predictions.add(settle(sample, classifier));
            }
        }

        return List.copyOf(predictions);
    }

    /** 记录实际注入的逻辑回归版本，区分原始量纲与标准化实验。 */
    public String logisticVersion() {
        return logisticTrainer.name();
    }

    private List<GoldSample> trainingData(
            List<GoldSample> initial,
            List<GoldSample> previous,
            LocalDate blockStart
    ) {
        List<GoldSample> result = new ArrayList<>(initial);
        previous.stream()
                .filter(sample -> sample.targetDate().isBefore(blockStart))
                .forEach(result::add);
        return result;
    }

    private SettledPrediction settle(
            GoldSample sample,
            GoldClassifier classifier
    ) {
        DirectionProbabilities probabilities = classifier.predict(sample.features());
        return new SettledPrediction(
                sample.asOfDate(),
                probabilities,
                policy.apply(probabilities),
                sample.label()
        );
    }

    private WalkForwardReport report(
            ForecastHorizon horizon,
            TemporalDataset data,
            Map<ModelType, List<SettledPrediction>> predictions,
            int refits
    ) {
        List<GoldSample> validation = data.validation();
        List<GoldSample> holdout = data.finalHoldout();
        Map<ModelType, ForecastMetrics> metrics = new EnumMap<>(ModelType.class);
        for (Map.Entry<ModelType, List<SettledPrediction>> entry : predictions.entrySet()) {
            metrics.put(entry.getKey(), evaluator.evaluate(entry.getValue()));
        }
        return new WalkForwardReport(
                horizon,
                data.training().getFirst().asOfDate(),
                validation.getFirst().asOfDate(),
                validation.getLast().asOfDate(),
                validation.size(),
                REFIT_EVERY,
                refits,
                metrics,
                holdout.size(),
                holdout.getFirst().asOfDate(),
                holdout.getLast().asOfDate()
        );
    }
}
