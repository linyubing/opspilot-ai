package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;
import com.opspilot.ai.forecast.learning.ModelType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 实验摘要响应，不包含大型混淆矩阵。 */
public record ModelExperimentSummaryResponse(
        UUID id,
        UUID comparisonId,
        String horizon,
        String status,
        String featureProfile,
        String datasetHashPrefix,
        String featureVersion,
        String labelVersion,
        String splitVersion,
        String gitCommit,
        OffsetDateTime createdAt,
        BigDecimal majorityAccuracy,
        BigDecimal logisticAccuracy,
        BigDecimal xgboostAccuracy,
        BigDecimal balancedAccuracy,
        BigDecimal coverage,
        BigDecimal brierScore,
        BigDecimal logLoss,
        BigDecimal majorityBalancedAccuracy,
        BigDecimal relativeMajorityImprovement,
        BigDecimal relativeBase16Improvement,
        String dataPolicy,
        BigDecimal fullBalancedAccuracy,
        BigDecimal fullMajorityBalancedAccuracy
) {
    public static ModelExperimentSummaryResponse from(
            ModelExperiment experiment,
            Map<ModelType, ModelExperimentMetric> metrics
    ) {
        ModelExperimentMetric majority = metrics.get(ModelType.MAJORITY);
        ModelExperimentMetric logistic = metrics.get(ModelType.LOGISTIC);
        ModelExperimentMetric xgboost = metrics.get(ModelType.XGBOOST);

        BigDecimal logAccuracy = logistic != null ? logistic.accuracy() : null;
        // 保留既有信号子集字段；只有配对完整日期指标才能用于相对比较。
        BigDecimal logBalanced = logistic != null ? logistic.balancedAccuracy() : null;
        BigDecimal logFullBalanced = logistic != null && logistic.paired() != null
                ? logistic.paired().all().balancedAccuracy() : null;
        BigDecimal logCoverage = logistic != null ? logistic.coverage() : null;
        BigDecimal logBrier = logistic != null ? logistic.brierScore() : null;
        BigDecimal logLogLoss = logistic != null ? logistic.logLoss() : null;
        BigDecimal majBalanced = majority != null ? majority.balancedAccuracy() : null;
        BigDecimal majFullBalanced = majority != null && majority.paired() != null
                ? majority.paired().all().balancedAccuracy() : null;
        BigDecimal xgbAccuracy = xgboost != null ? xgboost.accuracy() : null;

        BigDecimal relMajority = computeImprovement(logFullBalanced, majFullBalanced);

        return new ModelExperimentSummaryResponse(
                experiment.id(),
                experiment.comparisonId(),
                experiment.horizon(),
                experiment.status().name(),
                experiment.featureProfile().name(),
                experiment.datasetHash().substring(0, Math.min(12, experiment.datasetHash().length())),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.splitVersion(),
                experiment.gitCommit(),
                experiment.createdAt(),
                majority != null ? majority.accuracy() : null,
                logAccuracy,
                xgbAccuracy,
                logBalanced,
                logCoverage,
                logBrier,
                logLogLoss,
                majBalanced,
                relMajority,
                null,
                experiment.dataPolicy(),
                logFullBalanced,
                majFullBalanced
        );
    }

    public static List<ModelExperimentSummaryResponse> fillBase16Improvements(
            List<ModelExperimentSummaryResponse> summaries
    ) {
        List<ModelExperimentSummaryResponse> result = new ArrayList<>();
        for (ModelExperimentSummaryResponse s : summaries) {
            if ("BASE_16".equals(s.featureProfile()) || s.fullBalancedAccuracy() == null) {
                result.add(s);
                continue;
            }
            BigDecimal base16Balanced = summaries.stream()
                    .filter(other -> "BASE_16".equals(other.featureProfile())
                            && other.fullBalancedAccuracy() != null
                            && other.comparisonId() != null
                            && other.comparisonId().equals(s.comparisonId()))
                    .findFirst()
                    .map(ModelExperimentSummaryResponse::fullBalancedAccuracy)
                    .orElse(null);

            if (base16Balanced != null) {
                BigDecimal improvement = computeImprovement(s.fullBalancedAccuracy(), base16Balanced);
                result.add(new ModelExperimentSummaryResponse(
                        s.id(), s.comparisonId(), s.horizon(), s.status(),
                        s.featureProfile(), s.datasetHashPrefix(),
                        s.featureVersion(), s.labelVersion(), s.splitVersion(),
                        s.gitCommit(), s.createdAt(),
                        s.majorityAccuracy(), s.logisticAccuracy(), s.xgboostAccuracy(),
                        s.balancedAccuracy(), s.coverage(),
                        s.brierScore(), s.logLoss(),
                        s.majorityBalancedAccuracy(),
                        s.relativeMajorityImprovement(),
                        improvement,
                        s.dataPolicy(),
                        s.fullBalancedAccuracy(),
                        s.fullMajorityBalancedAccuracy()
                ));
            } else {
                result.add(s);
            }
        }
        return result;
    }

    private static BigDecimal computeImprovement(BigDecimal current, BigDecimal baseline) {
        if (current == null || baseline == null || baseline.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(baseline).divide(baseline, 4, RoundingMode.HALF_UP);
    }
}
