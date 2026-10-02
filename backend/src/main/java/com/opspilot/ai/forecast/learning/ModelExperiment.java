package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 黄金监督学习模型实验记录。 */
public record ModelExperiment(
        UUID id,
        UUID comparisonId,
        String horizon,
        String featureVersion,
        String labelVersion,
        String splitVersion,
        String datasetHash,
        FeatureProfile featureProfile,
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
    /** 无来源字段的旧记录仍是旧口径，不能因升级程序就声称已修正。 */
    public String dataPolicy() {
        if (parameters != null && parameters.get("macroInput") instanceof Map<?, ?> input
                && input.get("policy") instanceof String policy && !policy.isBlank()) return policy;
        return "legacy-latest-version";
    }
}
