package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 实验摘要响应，不包含大型混淆矩阵。 */
public record ModelExperimentSummaryResponse(
        UUID id,
        String horizon,
        String status,
        String featureProfile,
        String datasetHashPrefix,
        String featureVersion,
        String labelVersion,
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
                experiment.horizon(),
                experiment.status().name(),
                experiment.featureProfile(),
                experiment.datasetHash().substring(0, Math.min(12, experiment.datasetHash().length())),
                experiment.featureVersion(),
                experiment.labelVersion(),
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

    public static ModelExperimentSummaryResponse withBase16Improvement(
            ModelExperimentSummaryResponse base,
            BigDecimal base16LogisticBalanced
    ) {
        BigDecimal improvement = computeImprovement(
                base.balancedAccuracy(), base16LogisticBalanced
        );
        return new ModelExperimentSummaryResponse(
                base.id(), base.horizon(), base.status(),
                base.featureProfile(), base.datasetHashPrefix(),
                base.featureVersion(), base.labelVersion(),
                base.gitCommit(), base.createdAt(),
                base.majorityAccuracy(), base.logisticAccuracy(),
                base.balancedAccuracy(), base.coverage(),
                base.brierScore(), base.logLoss(),
                base.majorityBalancedAccuracy(),
                base.relativeMajorityImprovement(),
                improvement
        );
    }

    private static BigDecimal computeImprovement(BigDecimal current, BigDecimal baseline) {
        if (current == null || baseline == null || baseline.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(baseline).divide(baseline, 4, RoundingMode.HALF_UP);
    }
}
