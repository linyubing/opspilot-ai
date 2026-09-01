package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.Test;
import org.tribuo.classification.xgboost.XGBoostClassificationTrainer;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XgboostGoldTrainerTests {

    private final XgboostProperties properties = new XgboostProperties(
            200, 0.03, 0.0, 3, 5, 0.8, 0.8, 1.0, 0.0, 1, 20260901
    );

    @Test
    void trainReturnsValidProbabilities() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties);
        List<GoldSample> samples = createSamples();
        Set<String> featureNames = GoldFeatures.NAMES;

        GoldClassifier classifier = trainer.train(samples, featureNames);

        GoldFeatures testFeatures = createFeatures(1.0, 0.5, 0.2);
        DirectionProbabilities probs = classifier.predict(testFeatures);

        assertThat(probs.bullish()).isFinite().isGreaterThanOrEqualTo(0);
        assertThat(probs.neutral()).isFinite().isGreaterThanOrEqualTo(0);
        assertThat(probs.bearish()).isFinite().isGreaterThanOrEqualTo(0);
        double sum = probs.bullish() + probs.neutral() + probs.bearish();
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void fixedSeedReproducibility() {
        XgboostGoldTrainer trainer1 = new XgboostGoldTrainer(properties);
        XgboostGoldTrainer trainer2 = new XgboostGoldTrainer(properties);
        List<GoldSample> samples = createSamples();
        Set<String> featureNames = GoldFeatures.NAMES;

        GoldClassifier c1 = trainer1.train(samples, featureNames);
        GoldClassifier c2 = trainer2.train(samples, featureNames);

        GoldFeatures testFeatures = createFeatures(1.0, 0.5, 0.2);
        DirectionProbabilities p1 = c1.predict(testFeatures);
        DirectionProbabilities p2 = c2.predict(testFeatures);

        assertThat(p1.bullish()).isEqualTo(p2.bullish());
        assertThat(p1.neutral()).isEqualTo(p2.neutral());
        assertThat(p1.bearish()).isEqualTo(p2.bearish());
    }

    @Test
    void nativeLibraryErrorThrowsModelUnavailable() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties) {
            @Override
            XGBoostClassificationTrainer buildTrainer() {
                throw new UnsatisfiedLinkError("test native library error");
            }
        };

        assertThatThrownBy(() -> trainer.train(createSamples(), GoldFeatures.NAMES))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("XGBoost");
    }

    @Test
    void nativeInitializationErrorThrowsModelUnavailable() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties) {
            @Override
            XGBoostClassificationTrainer buildTrainer() {
                throw new ExceptionInInitializerError("test native initialization error");
            }
        };

        assertThatThrownBy(() -> trainer.train(createSamples(), GoldFeatures.NAMES))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("XGBoost");
    }

    @Test
    void supportsAllFeatureProfiles() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties);

        for (FeatureProfile profile : FeatureProfile.values()) {
            GoldClassifier classifier = trainer.train(createSamples(), profile.featureNames());
            DirectionProbabilities probabilities = classifier.predict(createFeatures(0.4, 0.2, 0.1));
            assertThat(probabilities.bullish() + probabilities.neutral() + probabilities.bearish())
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void excludedOhlcFeatureDoesNotAffectBasePrediction() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties);
        GoldClassifier classifier = trainer.train(createSamples(), FeatureProfile.BASE_16.featureNames());
        GoldFeatures first = createFeatures(0.4, 0.2, 0.1);
        Map<String, Double> changed = new HashMap<>(first.values());
        GoldOhlcFeatures.NAMES.forEach(name -> changed.put(name, 9999.0));

        DirectionProbabilities left = classifier.predict(first);
        DirectionProbabilities right = classifier.predict(new GoldFeatures(changed));

        assertThat(right).isEqualTo(left);
    }

    @Test
    void emptySamplesThrowsIllegalArgument() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties);

        assertThatThrownBy(() -> trainer.train(List.of(), GoldFeatures.NAMES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("训练样本不能为空");
    }

    @Test
    void nullSamplesThrowsIllegalArgument() {
        XgboostGoldTrainer trainer = new XgboostGoldTrainer(properties);

        assertThatThrownBy(() -> trainer.train(null, GoldFeatures.NAMES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<GoldSample> createSamples() {
        return List.of(
                createSample(com.opspilot.ai.forecast.ForecastDirection.BULLISH, 1.0, 0.8, 0.1),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BULLISH, 0.9, 0.7, 0.2),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BEARISH, -1.0, -0.8, -0.1),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BEARISH, -0.9, -0.7, -0.2),
                createSample(com.opspilot.ai.forecast.ForecastDirection.NEUTRAL, 0.1, 0.1, 0.5),
                createSample(com.opspilot.ai.forecast.ForecastDirection.NEUTRAL, -0.1, -0.1, 0.5),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BULLISH, 0.8, 0.6, 0.3),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BEARISH, -0.8, -0.6, -0.3),
                createSample(com.opspilot.ai.forecast.ForecastDirection.NEUTRAL, 0.2, 0.0, 0.4),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BULLISH, 0.7, 0.5, 0.4),
                createSample(com.opspilot.ai.forecast.ForecastDirection.BEARISH, -0.7, -0.5, -0.4),
                createSample(com.opspilot.ai.forecast.ForecastDirection.NEUTRAL, 0.0, 0.2, 0.3)
        );
    }

    private GoldSample createSample(
            com.opspilot.ai.forecast.ForecastDirection direction,
            double f1, double f2, double f3
    ) {
        return new GoldSample(
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 2),
                ForecastHorizon.NEXT_DAY,
                createFeatures(f1, f2, f3),
                direction
        );
    }

    private GoldFeatures createFeatures(double f1, double f2, double f3) {
        Map<String, Double> values = new HashMap<>();
        for (String name : GoldFeatures.NAMES) {
            values.put(name, 0.0);
        }
        values.put("gold_return_1", f1);
        values.put("gold_return_5", f2);
        values.put("gold_return_20", f3);
        return new GoldFeatures(values);
    }
}
