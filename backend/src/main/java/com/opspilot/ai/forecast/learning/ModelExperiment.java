package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 黄金监督学习模型实验记录。 */
public record ModelExperiment(
        UUID id,
        String horizon,
        String featureVersion,
        String labelVersion,
        String splitVersion,
        String datasetHash,
        String featureProfile,
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
        ModelExperimentStatus status,
        String gitCommit,
        String failureMessage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
