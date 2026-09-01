package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelExperimentResult;

import java.util.List;
import java.util.UUID;

/** compare接口响应，包含批次ID和三个实验详情。 */
public record CompareResponse(
        UUID comparisonId,
        String horizon,
        List<ModelExperimentDetailResponse> experiments
) {
    public static CompareResponse from(List<ModelExperimentResult> results) {
        UUID comparisonId = results.isEmpty() ? null : results.getFirst().experiment().comparisonId();
        String horizon = results.isEmpty() ? null : results.getFirst().experiment().horizon();
        List<ModelExperimentDetailResponse> experiments = results.stream()
                .map(ModelExperimentDetailResponse::from)
                .toList();
        return new CompareResponse(comparisonId, horizon, experiments);
    }
}
