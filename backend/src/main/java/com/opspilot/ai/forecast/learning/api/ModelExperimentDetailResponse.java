package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ForecastMetrics;
import com.opspilot.ai.forecast.learning.ModelExperiment;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 实验详情响应，包含完整元数据和参数。 */
public record ModelExperimentDetailResponse(
        UUID id,
        String horizon,
        String status,
        String datasetHash,
        String featureVersion,
        String labelVersion,
        String splitVersion,
        Map<String, Object> parameters,
        LocalDate dataStart,
        LocalDate dataEnd,
        LocalDate trainStart,
        LocalDate validationStart,
        LocalDate validationEnd,
        LocalDate holdoutStart,
        LocalDate holdoutEnd,
        int validationSamples,
        int holdoutSamples,
        String gitCommit,
        String failureMessage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        ForecastMetrics majority,
        ForecastMetrics logistic
) {
    public static ModelExperimentDetailResponse from(ModelExperiment experiment) {
        return new ModelExperimentDetailResponse(
                experiment.id(),
                experiment.horizon(),
                experiment.status().name(),
                experiment.datasetHash(),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.splitVersion(),
                experiment.parameters(),
                experiment.dataStart(),
                experiment.dataEnd(),
                experiment.trainStart(),
                experiment.validationStart(),
                experiment.validationEnd(),
                experiment.holdoutStart(),
                experiment.holdoutEnd(),
                experiment.validationSamples(),
                experiment.holdoutSamples(),
                experiment.gitCommit(),
                experiment.failureMessage(),
                experiment.createdAt(),
                experiment.startedAt(),
                experiment.completedAt(),
                null,
                null
        );
    }
}
