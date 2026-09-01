package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelExperiment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 实验摘要响应，不包含大型混淆矩阵。 */
public record ModelExperimentSummaryResponse(
        UUID id,
        String horizon,
        String status,
        String datasetHashPrefix,
        String featureVersion,
        String labelVersion,
        String gitCommit,
        OffsetDateTime createdAt
) {
    public static ModelExperimentSummaryResponse from(ModelExperiment experiment) {
        return new ModelExperimentSummaryResponse(
                experiment.id(),
                experiment.horizon(),
                experiment.status().name(),
                experiment.datasetHash().substring(0, Math.min(12, experiment.datasetHash().length())),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.gitCommit(),
                experiment.createdAt()
        );
    }
}
