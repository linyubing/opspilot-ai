package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.FeatureProfile;
import com.opspilot.ai.forecast.learning.ModelType;
import com.opspilot.ai.forecast.learning.WalkForwardReport;

import java.time.LocalDate;

/** 即时预览实验结果响应。 */
public record ModelExperimentResponse(
        String horizon,
        String featureProfile,
        LocalDate trainStart,
        LocalDate validationStart,
        LocalDate validationEnd,
        int validationSamples,
        int refitEvery,
        int refitCount,
        MetricResponse majority,
        MetricResponse logistic,
        MetricResponse xgboost,
        FinalHoldout finalHoldout
) {
    public record FinalHoldout(
            int samples,
            LocalDate start,
            LocalDate end
    ) {}

    public static ModelExperimentResponse from(WalkForwardReport report, FeatureProfile profile) {
        return new ModelExperimentResponse(
                report.horizon().name(),
                profile.name(),
                report.trainStart(),
                report.validationStart(),
                report.validationEnd(),
                report.validationSamples(),
                report.refitEvery(),
                report.refitCount(),
                MetricResponse.from(report.metric(ModelType.MAJORITY)),
                MetricResponse.from(report.metric(ModelType.LOGISTIC)),
                MetricResponse.from(report.metric(ModelType.XGBOOST)),
                new FinalHoldout(
                        report.finalHoldoutSamples(),
                        report.finalHoldoutStart(),
                        report.finalHoldoutEnd()
                )
        );
    }
}
