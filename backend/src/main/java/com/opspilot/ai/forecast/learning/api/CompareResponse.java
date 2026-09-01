package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ModelComparisonResult;
import com.opspilot.ai.forecast.learning.Stage8Candidate;

import java.util.List;
import java.util.UUID;

/** compare接口响应，包含批次ID、三个实验详情和阶段8候选判断。 */
public record CompareResponse(
        UUID comparisonId,
        String horizon,
        List<ModelExperimentDetailResponse> experiments,
        boolean stage8Candidate,
        String candidateProfile,
        String candidateReason
) {
    public static CompareResponse from(ModelComparisonResult result) {
        List<ModelExperimentDetailResponse> experiments = result.experiments().stream()
                .map(ModelExperimentDetailResponse::from)
                .toList();
        Stage8Candidate candidate = result.candidate();
        return new CompareResponse(
                result.comparisonId(),
                result.horizon().name(),
                experiments,
                candidate.passed(),
                candidate.profile() != null ? candidate.profile().name() : null,
                candidate.reason()
        );
    }
}
