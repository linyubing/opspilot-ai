package com.opspilot.ai.forecast.learning;

import java.util.List;
import java.util.UUID;

/** 保存一次单周期特征组合对比的完整结果。 */
public record ModelComparisonResult(
        UUID comparisonId,
        ForecastHorizon horizon,
        List<ModelExperimentResult> experiments,
        Stage8Candidate candidate
) {
}
