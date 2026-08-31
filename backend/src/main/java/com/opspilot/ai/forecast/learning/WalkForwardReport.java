package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;

/** 保存开发区间滚动验证结果，不暴露最终留出集标签。 */
public record WalkForwardReport(
        ForecastHorizon horizon,
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
}
