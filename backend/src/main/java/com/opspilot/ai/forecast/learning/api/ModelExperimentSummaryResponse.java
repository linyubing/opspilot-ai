package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
        BigDecimal balancedAccuracy,
        BigDecimal coverage,
        BigDecimal brierScore,
        BigDecimal logLoss,
        BigDecimal majorityBalancedAccuracy,
        BigDecimal relativeMajorityImprovement,
        BigDecimal relativeBase16Improvement
) {
    public static ModelExperimentSummaryResponse from(
            ModelExperiment experiment,
            ModelExperimentMetric majority,
            ModelExperimentMetric logistic
    ) {
        BigDecimal logAccuracy = logistic != null ? logistic.accuracy() : null;
        BigDecimal logBalanced = logistic != null ? logistic.balancedAccuracy() : null;
        BigDecimal logCoverage = logistic != null ? logistic.coverage() : null;
        BigDecimal logBrier = logistic != null ? logistic.brierScore() : null;
        BigDecimal logLogLoss = logistic != null ? logistic.logLoss() : null;
        BigDecimal majBalanced = majority != null ? majority.balancedAccuracy() : null;

        BigDecimal relMajority = computeImprovement(logBalanced, majBalanced);

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
                logBalanced,
                logCoverage,
                logBrier,
                logLogLoss,
                majBalanced,
                relMajority,
                null
        );
    }

    public static List<ModelExperimentSummaryResponse> fillBase16Improvements(
            List<ModelExperimentSummaryResponse> summaries
    ) {
        List<ModelExperimentSummaryResponse> result = new ArrayList<>();
        for (ModelExperimentSummaryResponse s : summaries) {
            if ("BASE_16".equals(s.featureProfile()) || s.balancedAccuracy() == null) {
                result.add(s);
                continue;
            }
            BigDecimal base16Balanced = summaries.stream()
                    .filter(other -> "BASE_16".equals(other.featureProfile())
                            && other.balancedAccuracy() != null
                            && other.comparisonId() != null
                            && other.comparisonId().equals(s.comparisonId()))
                    .findFirst()
                    .map(ModelExperimentSummaryResponse::balancedAccuracy)
                    .orElse(null);

            if (base16Balanced != null) {
                BigDecimal improvement = computeImprovement(s.balancedAccuracy(), base16Balanced);
                result.add(new ModelExperimentSummaryResponse(
                        s.id(), s.comparisonId(), s.horizon(), s.status(),
                        s.featureProfile(), s.datasetHashPrefix(),
                        s.featureVersion(), s.labelVersion(), s.splitVersion(),
                        s.gitCommit(), s.createdAt(),
                        s.majorityAccuracy(), s.logisticAccuracy(),
                        s.balancedAccuracy(), s.coverage(),
                        s.brierScore(), s.logLoss(),
                        s.majorityBalancedAccuracy(),
                        s.relativeMajorityImprovement(),
                        improvement
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
