package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Stage8CandidateEvaluatorTests {

    private final Stage8CandidateEvaluator evaluator = new Stage8CandidateEvaluator();

    @Test
    void allThresholdsPassed() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.32"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.38"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.30"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isTrue();
        assertThat(candidate.profile()).isNotNull();
        assertThat(candidate.reason()).contains("通过全部门槛");
    }

    @Test
    void balancedAccuracyTooLow() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.71"), new BigDecimal("0.70"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.69"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.32"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.38"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.66"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.30"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.profile()).isNull();
        assertThat(candidate.reason()).contains("平衡准确率");
    }

    @Test
    void accuracyNotAboveMajority() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.50"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.50"), new BigDecimal("0.32"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.38"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.50"), new BigDecimal("0.30"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.reason()).contains("已覆盖信号准确率");
    }

    @Test
    void coverageBelowThreshold() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.25"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.25"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.38"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.25"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.reason()).contains("覆盖率");
    }

    @Test
    void recallBelowThreshold() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.50"), new BigDecimal("0.20")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.32"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.38"), new BigDecimal("0.50"), new BigDecimal("0.20")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.30"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.20"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.reason()).contains("召回率");
    }

    @Test
    void brierScoreHigher() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.25"), new BigDecimal("0.20"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.32"),
                        new BigDecimal("0.25"), new BigDecimal("0.20"),
                        new BigDecimal("0.38"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.30"),
                        new BigDecimal("0.25"), new BigDecimal("0.20"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.reason()).contains("Brier Score");
    }

    @Test
    void logLossHigher() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.90"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.32"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.90"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.30"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.90"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.reason()).contains("Log Loss");
    }

    @Test
    void multipleProfilesPassedSelectsBest() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.78"), new BigDecimal("0.76"),
                        new BigDecimal("0.88"), new BigDecimal("0.38"),
                        new BigDecimal("0.12"), new BigDecimal("0.82"),
                        new BigDecimal("0.38"), new BigDecimal("0.62"), new BigDecimal("0.34")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.72"), new BigDecimal("0.70"),
                        new BigDecimal("0.82"), new BigDecimal("0.33"),
                        new BigDecimal("0.16"), new BigDecimal("0.78"),
                        new BigDecimal("0.42"), new BigDecimal("0.58"), new BigDecimal("0.36"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isTrue();
        assertThat(candidate.profile()).isEqualTo(FeatureProfile.OHLC_20);
    }

    @Test
    void noProfilePassedReturnsClosest() {
        List<ModelExperimentResult> experiments = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.50"), new BigDecimal("0.25"),
                        new BigDecimal("0.30"), new BigDecimal("0.80"),
                        new BigDecimal("0.90"), new BigDecimal("0.60"), new BigDecimal("0.20")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.65"), new BigDecimal("0.62"),
                        new BigDecimal("0.50"), new BigDecimal("0.20"),
                        new BigDecimal("0.25"), new BigDecimal("0.75"),
                        new BigDecimal("0.85"), new BigDecimal("0.55"), new BigDecimal("0.20")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.60"), new BigDecimal("0.58"),
                        new BigDecimal("0.50"), new BigDecimal("0.15"),
                        new BigDecimal("0.35"), new BigDecimal("0.70"),
                        new BigDecimal("0.80"), new BigDecimal("0.50"), new BigDecimal("0.20"))
        );

        Stage8Candidate candidate = evaluator.evaluate(experiments);

        assertThat(candidate.passed()).isFalse();
        assertThat(candidate.profile()).isNull();
        assertThat(candidate.reason()).isNotEmpty();
    }

    @Test
    void orderIndependence() {
        List<ModelExperimentResult> experiments1 = List.of(
                createExperiment(FeatureProfile.BASE_16,
                        new BigDecimal("0.75"), new BigDecimal("0.72"),
                        new BigDecimal("0.85"), new BigDecimal("0.35"),
                        new BigDecimal("0.15"), new BigDecimal("0.80"),
                        new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35")),
                createExperiment(FeatureProfile.OHLC_20,
                        new BigDecimal("0.70"), new BigDecimal("0.68"),
                        new BigDecimal("0.80"), new BigDecimal("0.32"),
                        new BigDecimal("0.18"), new BigDecimal("0.75"),
                        new BigDecimal("0.38"), new BigDecimal("0.55"), new BigDecimal("0.33")),
                createExperiment(FeatureProfile.ALL_36,
                        new BigDecimal("0.68"), new BigDecimal("0.65"),
                        new BigDecimal("0.78"), new BigDecimal("0.30"),
                        new BigDecimal("0.20"), new BigDecimal("0.70"),
                        new BigDecimal("0.36"), new BigDecimal("0.50"), new BigDecimal("0.32"))
        );

        List<ModelExperimentResult> experiments2 = List.of(
                experiments1.get(2),
                experiments1.get(0),
                experiments1.get(1)
        );

        Stage8Candidate c1 = evaluator.evaluate(experiments1);
        Stage8Candidate c2 = evaluator.evaluate(experiments2);

        assertThat(c1.passed()).isEqualTo(c2.passed());
        assertThat(c1.profile()).isEqualTo(c2.profile());
    }

    private ModelExperimentResult createExperiment(
            FeatureProfile profile,
            BigDecimal xgbBalancedAccuracy, BigDecimal logBalancedAccuracy,
            BigDecimal xgbAccuracy, BigDecimal xgbCoverage,
            BigDecimal xgbBrier, BigDecimal logBrier,
            BigDecimal xgbLogLoss, BigDecimal logLogLoss,
            BigDecimal xgbRecallDown
    ) {
        UUID experimentId = UUID.randomUUID();
        ModelExperiment experiment = new ModelExperiment(
                experimentId, UUID.randomUUID(), "NEXT_DAY",
                GoldFeatures.VERSION, GoldForecastRule.RULE_VERSION, "1.0",
                "hash", profile, Map.of(),
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 9, 30), LocalDate.of(2025, 10, 1),
                LocalDate.of(2025, 12, 31), 100, 50,
                ModelExperimentStatus.COMPLETED, "7e57c99", null,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now()
        );

        Map<String, Object> xgbRecalls = new LinkedHashMap<>();
        xgbRecalls.put("BULLISH", new BigDecimal("0.40"));
        xgbRecalls.put("NEUTRAL", new BigDecimal("0.30"));
        xgbRecalls.put("BEARISH", xgbRecallDown);

        Map<String, Object> logRecalls = new LinkedHashMap<>();
        logRecalls.put("BULLISH", new BigDecimal("0.35"));
        logRecalls.put("NEUTRAL", new BigDecimal("0.25"));
        logRecalls.put("BEARISH", new BigDecimal("0.30"));

        Map<String, Object> majRecalls = new LinkedHashMap<>();
        majRecalls.put("BULLISH", new BigDecimal("0.30"));
        majRecalls.put("NEUTRAL", new BigDecimal("0.20"));
        majRecalls.put("BEARISH", new BigDecimal("0.25"));

        Map<ModelType, ModelExperimentMetric> metrics = new EnumMap<>(ModelType.class);
        metrics.put(ModelType.XGBOOST, new ModelExperimentMetric(
                experimentId, ModelType.XGBOOST, 200, 150,
                xgbCoverage, xgbAccuracy, xgbBalancedAccuracy, xgbBrier, xgbLogLoss,
                xgbRecalls, Map.of()
        ));
        metrics.put(ModelType.LOGISTIC, new ModelExperimentMetric(
                experimentId, ModelType.LOGISTIC, 200, 150,
                new BigDecimal("0.75"), new BigDecimal("0.65"), logBalancedAccuracy,
                logBrier, logLogLoss, logRecalls, Map.of()
        ));
        metrics.put(ModelType.MAJORITY, new ModelExperimentMetric(
                experimentId, ModelType.MAJORITY, 200, 200,
                new BigDecimal("1.00"), new BigDecimal("0.55"), new BigDecimal("0.50"),
                new BigDecimal("0.50"), new BigDecimal("1.00"), majRecalls, Map.of()
        ));

        return new ModelExperimentResult(experiment, metrics);
    }
}
