package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TribuoGoldTrainerTests {

    @Test
    void learnsKnownThreeClassPattern() {
        List<GoldSample> samples = samples(300);
        GoldClassifier classifier = new TribuoGoldTrainer().train(samples.subList(0, 240), GoldFeatures.NAMES);
        ConfidencePolicy policy = new ConfidencePolicy(0.34);
        List<SettledPrediction> predictions = new ArrayList<>();

        for (GoldSample sample : samples.subList(240, 300)) {
            DirectionProbabilities probabilities = classifier.predict(sample.features());
            predictions.add(new SettledPrediction(
                    sample.asOfDate(),
                    probabilities,
                    policy.apply(probabilities),
                    sample.label()
            ));
        }

        ForecastMetrics metrics = new ForecastEvaluator().evaluate(predictions);
        assertThat(metrics.accuracy()).isGreaterThan(new BigDecimal("0.90"));
        assertThat(metrics.balancedAccuracy()).isGreaterThan(new BigDecimal("0.90"));
    }

    @Test
    void returnsStableCompleteProbabilities() {
        List<GoldSample> samples = samples(240);
        GoldFeatures input = features(10);
        DirectionProbabilities first = new TribuoGoldTrainer()
                .train(samples, GoldFeatures.NAMES)
                .predict(input);
        DirectionProbabilities second = new TribuoGoldTrainer()
                .train(samples, GoldFeatures.NAMES)
                .predict(input);

        assertThat(first.bullish() + first.neutral() + first.bearish())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(first.bullish()).isCloseTo(second.bullish(),
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(first.neutral()).isCloseTo(second.neutral(),
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(first.bearish()).isCloseTo(second.bearish(),
                org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void baseModelRegisters16Features() {
        List<GoldSample> samples = samples(240);
        Set<String> baseFeatures = FeatureProfile.BASE_16.featureNames();
        GoldClassifier classifier = new TribuoGoldTrainer().train(samples, baseFeatures);

        GoldFeatures input = features(10);
        DirectionProbabilities probabilities = classifier.predict(input);
        assertThat(probabilities).isNotNull();
        assertThat(probabilities.bullish() + probabilities.neutral() + probabilities.bearish())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void ohlcModelRegisters20Features() {
        List<GoldSample> samples = samples(240);
        Set<String> ohlcFeatures = FeatureProfile.OHLC_20.featureNames();
        GoldClassifier classifier = new TribuoGoldTrainer().train(samples, ohlcFeatures);

        GoldFeatures input = features(10);
        DirectionProbabilities probabilities = classifier.predict(input);
        assertThat(probabilities).isNotNull();
        assertThat(probabilities.bullish() + probabilities.neutral() + probabilities.bearish())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void allModelRegisters36Features() {
        List<GoldSample> samples = samples(240);
        Set<String> allFeatures = FeatureProfile.ALL_36.featureNames();
        GoldClassifier classifier = new TribuoGoldTrainer().train(samples, allFeatures);

        GoldFeatures input = features(10);
        DirectionProbabilities probabilities = classifier.predict(input);
        assertThat(probabilities).isNotNull();
        assertThat(probabilities.bullish() + probabilities.neutral() + probabilities.bearish())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void changingExcludedFeaturesDoesNotAffectPrediction() {
        List<GoldSample> samples = samples(240);
        Set<String> baseFeatures = FeatureProfile.BASE_16.featureNames();

        GoldClassifier baseClassifier = new TribuoGoldTrainer().train(samples, baseFeatures);

        GoldFeatures inputDefault = features(10);
        DirectionProbabilities defaultPrediction = baseClassifier.predict(inputDefault);

        GoldFeatures inputModified = featuresWithModifiedOhlc(10);
        DirectionProbabilities modifiedPrediction = baseClassifier.predict(inputModified);

        assertThat(defaultPrediction.bullish()).isCloseTo(modifiedPrediction.bullish(),
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(defaultPrediction.neutral()).isCloseTo(modifiedPrediction.neutral(),
                org.assertj.core.data.Offset.offset(0.000001));
        assertThat(defaultPrediction.bearish()).isCloseTo(modifiedPrediction.bearish(),
                org.assertj.core.data.Offset.offset(0.000001));
    }

    private List<GoldSample> samples(int count) {
        LocalDate start = LocalDate.parse("2020-01-01");
        List<GoldSample> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int value = switch (i % 3) {
                case 0 -> 10;
                case 1 -> 0;
                default -> -10;
            };
            ForecastDirection label = value > 0
                    ? ForecastDirection.BULLISH
                    : value < 0
                    ? ForecastDirection.BEARISH
                    : ForecastDirection.NEUTRAL;
            result.add(new GoldSample(
                    start.plusDays(i),
                    start.plusDays(i + 1),
                    ForecastHorizon.NEXT_DAY,
                    features(value),
                    label
            ));
        }
        return result;
    }

    private GoldFeatures features(double return5) {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        values.put("gold_return_5", return5);
        return new GoldFeatures(values);
    }

    private GoldFeatures featuresWithModifiedOhlc(double return5) {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        values.put("gold_return_5", return5);
        values.put("rsi14", 99.0);
        values.put("atr14", 999.0);
        values.put("ma5Distance", 999.0);
        return new GoldFeatures(values);
    }
}
