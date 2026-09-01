package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ForecastMetrics;
import com.opspilot.ai.forecast.learning.WalkForwardReport;

import java.time.LocalDate;

/** 即时预览实验结果响应。 */
public record ModelExperimentResponse(
        String horizon,
        LocalDate trainStart,
        LocalDate validationStart,
        LocalDate validationEnd,
        int validationSamples,
        int refitEvery,
        int refitCount,
        ForecastMetrics majority,
        ForecastMetrics logistic,
        int finalHoldoutSamples,
        LocalDate finalHoldoutStart,
        LocalDate finalHoldoutEnd
) {
    public static ModelExperimentResponse from(WalkForwardReport report) {
        return new ModelExperimentResponse(
                report.horizon().name(),
                report.trainStart(),
                report.validationStart(),
                report.validationEnd(),
                report.validationSamples(),
                report.refitEvery(),
                report.refitCount(),
                report.majority(),
                report.logistic(),
                report.finalHoldoutSamples(),
                report.finalHoldoutStart(),
                report.finalHoldoutEnd()
        );
    }
}
