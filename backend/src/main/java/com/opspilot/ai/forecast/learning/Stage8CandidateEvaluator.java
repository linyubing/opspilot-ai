package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 根据开发验证指标判断阶段8候选，不负责模型晋级。 */
@Component
public class Stage8CandidateEvaluator {

    private static final BigDecimal BALANCED_ACCURACY_DELTA = new BigDecimal("0.02");
    private static final BigDecimal MIN_COVERAGE = new BigDecimal("0.30");
    private static final BigDecimal MIN_RECALL = new BigDecimal("0.25");

    public Stage8Candidate evaluate(List<ModelExperimentResult> experiments) {
        Map<FeatureProfile, String> reasons = new LinkedHashMap<>();
        List<FeatureProfile> passed = new ArrayList<>();

        for (ModelExperimentResult exp : experiments) {
            FeatureProfile profile = exp.experiment().featureProfile();
            ModelExperimentMetric xgb = exp.metrics().get(ModelType.XGBOOST);
            ModelExperimentMetric log = exp.metrics().get(ModelType.LOGISTIC);
            ModelExperimentMetric maj = exp.metrics().get(ModelType.MAJORITY);

            if (xgb == null || log == null || maj == null) {
                reasons.put(profile, "缺少必要模型指标");
                continue;
            }

            List<String> failed = new ArrayList<>();

            if (xgb.paired() == null || log.paired() == null) {
                reasons.put(profile, "缺少完整日期评分和同信号日期基线的配对证据");
                continue;
            }
            var xgbAll = xgb.paired().all();
            var logAll = log.paired().all();
            var sameDateBaseline = xgb.paired().baselineOnSignals();
            if (sameDateBaseline == null || xgb.coveredCount() == 0) {
                reasons.put(profile, "没有可比较的信号日期，不能成为候选");
                continue;
            }
            if (xgbAll.sampleCount() != logAll.sampleCount()) {
                reasons.put(profile, "完整日期评分样本数不一致，不能比较候选");
                continue;
            }

            if (xgbAll.balancedAccuracy() == null || logAll.balancedAccuracy() == null) {
                failed.add("部分类别样本不足，无法比较平衡准确率");
            } else {
                BigDecimal balancedDelta = xgbAll.balancedAccuracy().subtract(logAll.balancedAccuracy());
                if (balancedDelta.compareTo(BALANCED_ACCURACY_DELTA) < 0) {
                    failed.add("平衡准确率未比Logistic高2个百分点（差值=" + balancedDelta.setScale(4, RoundingMode.HALF_UP) + "）");
                }
            }

            if (xgb.accuracy().compareTo(sameDateBaseline.accuracy()) <= 0) {
                failed.add("已覆盖信号准确率(" + xgb.accuracy().setScale(4, RoundingMode.HALF_UP)
                        + ")未高于同信号日期Majority(" + sameDateBaseline.accuracy().setScale(4, RoundingMode.HALF_UP) + ")");
            }

            if (xgb.coverage().compareTo(MIN_COVERAGE) < 0) {
                failed.add("覆盖率(" + xgb.coverage().setScale(4, RoundingMode.HALF_UP) + ")低于30%");
            }

            for (ForecastDirection dir : ForecastDirection.values()) {
                BigDecimal recallObj = xgbAll.recalls().get(dir);
                if (recallObj == null) {
                    failed.add(dir + "方向召回率(null)低于25%");
                    continue;
                }
                BigDecimal recall = recallObj;
                if (recall.compareTo(MIN_RECALL) < 0) {
                    failed.add(dir + "方向召回率(" + recall.setScale(4, RoundingMode.HALF_UP) + ")低于25%");
                }
            }

            if (xgbAll.brierScore().compareTo(logAll.brierScore()) > 0) {
                failed.add("Brier Score(" + xgbAll.brierScore().setScale(4, RoundingMode.HALF_UP)
                        + ")高于Logistic(" + logAll.brierScore().setScale(4, RoundingMode.HALF_UP) + ")");
            }

            if (xgbAll.logLoss().compareTo(logAll.logLoss()) > 0) {
                failed.add("Log Loss(" + xgbAll.logLoss().setScale(4, RoundingMode.HALF_UP)
                        + ")高于Logistic(" + logAll.logLoss().setScale(4, RoundingMode.HALF_UP) + ")");
            }

            if (failed.isEmpty()) {
                passed.add(profile);
                reasons.put(profile, "通过全部门槛");
            } else {
                reasons.put(profile, String.join("；", failed));
            }
        }

        if (passed.isEmpty()) {
            String closestReason = reasons.values().stream()
                    .min(Comparator.comparingInt(r -> r.split("；").length))
                    .orElse("无实验结果");
            return new Stage8Candidate(false, null, closestReason);
        }

        passed.sort(Comparator
                .comparing((FeatureProfile p) -> findMetric(experiments, p, ModelType.XGBOOST).paired().all().balancedAccuracy(),
                        Comparator.reverseOrder())
                .thenComparing((FeatureProfile p) -> findMetric(experiments, p, ModelType.XGBOOST).paired().all().accuracy(),
                        Comparator.reverseOrder())
                .thenComparing((FeatureProfile p) -> findMetric(experiments, p, ModelType.XGBOOST).paired().all().brierScore())
                .thenComparing((FeatureProfile p) -> findMetric(experiments, p, ModelType.XGBOOST).paired().all().logLoss())
        );

        FeatureProfile winner = passed.getFirst();
        return new Stage8Candidate(
                true,
                winner,
                "通过全部门槛，" + winner + "在候选组合中平衡准确率最高"
        );
    }

    private ModelExperimentMetric findMetric(
            List<ModelExperimentResult> experiments,
            FeatureProfile profile,
            ModelType type
    ) {
        return experiments.stream()
                .filter(e -> e.experiment().featureProfile() == profile)
                .map(e -> e.metric(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "缺少实验结果: profile=" + profile + ", type=" + type
                ));
    }
}
