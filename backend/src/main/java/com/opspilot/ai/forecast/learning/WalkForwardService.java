package com.opspilot.ai.forecast.learning;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 在开发验证区间滚动重训并比较多数类与逻辑回归模型。 */
@Service
public class WalkForwardService {

    private static final int REFIT_EVERY = 20;
    private static final double CONFIDENCE = 0.55;

    private final GoldDatasetBuilder builder;
    private final TemporalSplitter splitter;
    private final GoldTrainer majorityTrainer;
    private final GoldTrainer logisticTrainer;
    private final ForecastEvaluator evaluator;
    private final ConfidencePolicy policy = new ConfidencePolicy(CONFIDENCE);

    public WalkForwardService(
            GoldDatasetBuilder builder,
            TemporalSplitter splitter,
            @Qualifier("majorityGoldTrainer") GoldTrainer majorityTrainer,
            @Qualifier("tribuoGoldTrainer") GoldTrainer logisticTrainer,
            ForecastEvaluator evaluator
    ) {
        this.builder = builder;
        this.splitter = splitter;
        this.majorityTrainer = majorityTrainer;
        this.logisticTrainer = logisticTrainer;
        this.evaluator = evaluator;
    }

    public WalkForwardReport run(ForecastHorizon horizon) {
        GoldDataset raw = builder.build(horizon);
        return run(raw, horizon);
    }

    public WalkForwardReport run(GoldDataset dataset, ForecastHorizon horizon) {
        TemporalDataset data = splitter.split(dataset.samples(), horizon);
        List<SettledPrediction> majorityPredictions = new ArrayList<>();
        List<SettledPrediction> logisticPredictions = new ArrayList<>();
        List<GoldSample> validation = data.validation();
        int refits = 0;

        for (int start = 0; start < validation.size(); start += REFIT_EVERY) {
            int end = Math.min(start + REFIT_EVERY, validation.size());
            List<GoldSample> block = validation.subList(start, end);
            List<GoldSample> training = trainingData(
                    data.training(),
                    validation.subList(0, start),
                    block.getFirst().asOfDate()
            );
            GoldClassifier majority = majorityTrainer.train(training);
            GoldClassifier logistic = logisticTrainer.train(training);
            refits++;

            for (GoldSample sample : block) {
                majorityPredictions.add(settle(sample, majority));
                logisticPredictions.add(settle(sample, logistic));
            }
        }

        return report(
                horizon,
                data,
                majorityPredictions,
                logisticPredictions,
                refits
        );
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
            List<SettledPrediction> majority,
            List<SettledPrediction> logistic,
            int refits
    ) {
        List<GoldSample> validation = data.validation();
        List<GoldSample> holdout = data.finalHoldout();
        return new WalkForwardReport(
                horizon,
                data.training().getFirst().asOfDate(),
                validation.getFirst().asOfDate(),
                validation.getLast().asOfDate(),
                validation.size(),
                REFIT_EVERY,
                refits,
                evaluator.evaluate(majority),
                evaluator.evaluate(logistic),
                holdout.size(),
                holdout.getFirst().asOfDate(),
                holdout.getLast().asOfDate()
        );
    }
}
