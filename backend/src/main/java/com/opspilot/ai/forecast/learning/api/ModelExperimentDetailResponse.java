package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;
import com.opspilot.ai.forecast.learning.ModelExperimentResult;
import com.opspilot.ai.forecast.learning.ModelType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 实验详情响应，包含完整元数据和参数。 */
public record ModelExperimentDetailResponse(
        UUID id,
        UUID comparisonId,
        String horizon,
        String status,
        String datasetHash,
        String featureVersion,
        String labelVersion,
        String splitVersion,
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
        String gitCommit,
        String failureMessage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        MetricResponse majority,
        MetricResponse logistic,
        MetricResponse xgboost
) {
    public static ModelExperimentDetailResponse from(ModelExperimentResult result) {
        ModelExperiment experiment = result.experiment();
        return new ModelExperimentDetailResponse(
                experiment.id(),
                experiment.comparisonId(),
                experiment.horizon(),
                experiment.status().name(),
                experiment.datasetHash(),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.splitVersion(),
                experiment.featureProfile().name(),
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
                MetricResponse.from(result.metric(ModelType.MAJORITY)),
                MetricResponse.from(result.metric(ModelType.LOGISTIC)),
                MetricResponse.from(result.metric(ModelType.XGBOOST))
        );
    }

    public static ModelExperimentDetailResponse from(
            ModelExperiment experiment,
            Map<ModelType, ModelExperimentMetric> metrics
    ) {
        ModelExperimentMetric majority = metrics.get(ModelType.MAJORITY);
        ModelExperimentMetric logistic = metrics.get(ModelType.LOGISTIC);
        ModelExperimentMetric xgboost = metrics.get(ModelType.XGBOOST);
        return new ModelExperimentDetailResponse(
                experiment.id(),
                experiment.comparisonId(),
                experiment.horizon(),
                experiment.status().name(),
                experiment.datasetHash(),
                experiment.featureVersion(),
                experiment.labelVersion(),
                experiment.splitVersion(),
                experiment.featureProfile().name(),
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
                majority != null ? MetricResponse.from(majority) : null,
                logistic != null ? MetricResponse.from(logistic) : null,
                xgboost != null ? MetricResponse.from(xgboost) : null
        );
    }
}
