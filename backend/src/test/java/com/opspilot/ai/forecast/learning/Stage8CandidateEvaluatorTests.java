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
    void rejectsZeroSignalsWithoutInventingBaseline() {
        var exp = createExperiment(FeatureProfile.BASE_16,
                new BigDecimal("0.75"), new BigDecimal("0.72"),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.15"), new BigDecimal("0.80"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35"));
        var result = evaluator.evaluate(List.of(exp));
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("没有可比较的信号日期");
        assertThat(exp.metric(ModelType.XGBOOST).paired().baselineOnSignals()).isNull();
    }

    @Test
    void breaksTiesUsingFullAccuracy() {
        // 不同组合的信号日期不同，不能用筛选后的高准确率决定胜者。
        var base = createExperiment(FeatureProfile.BASE_16,
                new BigDecimal("0.75"), new BigDecimal("0.72"),
                new BigDecimal("0.85"), new BigDecimal("0.35"),
                new BigDecimal("0.15"), new BigDecimal("0.80"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35"));
        var ohlc = createExperiment(FeatureProfile.OHLC_20,
                new BigDecimal("0.75"), new BigDecimal("0.72"),
                new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("0.15"), new BigDecimal("0.80"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35"));
        var metrics = new EnumMap<>(base.metrics());
        var m = metrics.get(ModelType.XGBOOST);
        var all = m.paired().all();
        var lower = new ForecastMetrics(all.sampleCount(), all.coveredCount(), all.coverage(),
                new BigDecimal("0.60"), all.balancedAccuracy(), all.brierScore(), all.logLoss(),
                all.recalls(), all.confusionMatrix(), false);
        metrics.put(ModelType.XGBOOST, new ModelExperimentMetric(m.experimentId(), m.modelType(),
                m.sampleCount(), m.coveredCount(), m.coverage(), m.accuracy(), m.balancedAccuracy(),
                m.brierScore(), m.logLoss(), m.recalls(), m.confusionMatrix(),
                new PairedMetrics(lower, m.paired().baselineOnSignals(), m.paired().signalDates())));
        var result = evaluator.evaluate(List.of(new ModelExperimentResult(base.experiment(), metrics), ohlc));
        assertThat(result.profile()).isEqualTo(FeatureProfile.OHLC_20);
    }

    @Test
    void rejectsWhenOnlyGlobalBaselineIsBeaten() {
        var exp = createExperiment(FeatureProfile.BASE_16,
                new BigDecimal("0.75"), new BigDecimal("0.72"),
                new BigDecimal("0.85"), new BigDecimal("0.35"),
                new BigDecimal("0.15"), new BigDecimal("0.80"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35"));
        var metrics = new EnumMap<>(exp.metrics());
        metrics.put(ModelType.XGBOOST, paired(metrics.get(ModelType.XGBOOST), new BigDecimal("0.90")));
        var result = evaluator.evaluate(List.of(new ModelExperimentResult(exp.experiment(), metrics)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("同信号日期Majority");
    }

    @Test
    void acceptsSameDateImprovementEvenBelowGlobalBaseline() {
        var exp = createExperiment(FeatureProfile.BASE_16,
                new BigDecimal("0.75"), new BigDecimal("0.72"),
                new BigDecimal("0.54"), new BigDecimal("0.35"),
                new BigDecimal("0.15"), new BigDecimal("0.80"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35"));
        var metrics = new EnumMap<>(exp.metrics());
        metrics.put(ModelType.XGBOOST, paired(metrics.get(ModelType.XGBOOST), new BigDecimal("0.30")));
        var result = evaluator.evaluate(List.of(new ModelExperimentResult(exp.experiment(), metrics)));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void rejectsMetricsWithoutPairedDates() {
        // 旧汇总指标即使看似满足门槛，也不能拿不同信号日期直接比较。
        var exp = createExperiment(FeatureProfile.BASE_16,
                new BigDecimal("0.75"), new BigDecimal("0.72"),
                new BigDecimal("0.85"), new BigDecimal("0.35"),
                new BigDecimal("0.15"), new BigDecimal("0.80"),
                new BigDecimal("0.40"), new BigDecimal("0.60"), new BigDecimal("0.35"));
        Map<ModelType, ModelExperimentMetric> old = new EnumMap<>(ModelType.class);
        exp.metrics().forEach((type, m) -> old.put(type, new ModelExperimentMetric(
                m.experimentId(), type, m.sampleCount(), m.coveredCount(), m.coverage(),
                m.accuracy(), m.balancedAccuracy(), m.brierScore(), m.logLoss(),
                m.recalls(), m.confusionMatrix())));
        var result = evaluator.evaluate(List.of(new ModelExperimentResult(exp.experiment(), old)));
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("配对");
    }

    @Test
    void missingBalancedScoreRejectsCandidate() {
        // 部分类别没有已覆盖样本时，不能算平衡准确率；应拒绝候选而非使整批实验崩溃。
        ModelExperimentResult exp = createExperiment(FeatureProfile.BASE_16,
                null, new BigDecimal("0.50"), new BigDecimal("0.80"),
                new BigDecimal("0.70"), new BigDecimal("0.20"), new BigDecimal("0.30"),
                new BigDecimal("0.40"), new BigDecimal("0.50"), new BigDecimal("0.30"));
        Stage8Candidate result = evaluator.evaluate(List.of(exp));
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("平衡准确率", "不足");
    }

    @Test
    void missingLogisticScoreDoesNotImplyImprovement() {
        ModelExperimentResult exp = createExperiment(FeatureProfile.BASE_16,
                new BigDecimal("0.60"), null, new BigDecimal("0.80"),
                new BigDecimal("0.70"), new BigDecimal("0.20"), new BigDecimal("0.30"),
                new BigDecimal("0.40"), new BigDecimal("0.50"), new BigDecimal("0.30"));
        Stage8Candidate result = evaluator.evaluate(List.of(exp));
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("平衡准确率", "不足");
    }

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
                experimentId, ModelType.XGBOOST, 200, xgbCoverage.multiply(new BigDecimal("200")).intValue(),
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

        metrics.replaceAll((type, metric) -> paired(metric, new BigDecimal("0.55")));
        return new ModelExperimentResult(experiment, metrics);
    }

    private ModelExperimentMetric paired(ModelExperimentMetric metric, BigDecimal baselineAccuracy) {
        var recalls = new EnumMap<ForecastDirection, BigDecimal>(ForecastDirection.class);
        for (var direction : ForecastDirection.values()) {
            recalls.put(direction, (BigDecimal) metric.recalls().get(direction.name()));
        }
        var all = new ForecastMetrics(metric.sampleCount(), metric.sampleCount(), BigDecimal.ONE,
                metric.accuracy(), metric.balancedAccuracy(), metric.brierScore(), metric.logLoss(),
                recalls, Map.of(), false);
        var baseline = metric.coveredCount() == 0 ? null : new ForecastMetrics(
                metric.coveredCount(), metric.coveredCount(), BigDecimal.ONE, baselineAccuracy,
                new BigDecimal("0.5"), new BigDecimal("0.5"), BigDecimal.ONE, recalls, Map.of(), false);
        var dates = java.util.stream.IntStream.range(0, metric.coveredCount())
                .mapToObj(i -> LocalDate.of(2025, 1, 1).plusDays(i)).toList();
        return new ModelExperimentMetric(metric.experimentId(), metric.modelType(), metric.sampleCount(),
                metric.coveredCount(), metric.coverage(), metric.accuracy(), metric.balancedAccuracy(),
                metric.brierScore(), metric.logLoss(), metric.recalls(), metric.confusionMatrix(),
                new PairedMetrics(all, baseline, dates));
    }
}
