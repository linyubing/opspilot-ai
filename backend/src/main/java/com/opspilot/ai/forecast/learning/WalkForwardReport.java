package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.Map;

/** 保存开发区间滚动验证结果，不暴露最终留出集标签。 */
public record WalkForwardReport(
        ForecastHorizon horizon,
        LocalDate trainStart,
        LocalDate validationStart,
        LocalDate validationEnd,
        int validationSamples,
        int refitEvery,
        int refitCount,
        Map<ModelType, ForecastMetrics> metrics,
        int finalHoldoutSamples,
        LocalDate finalHoldoutStart,
        LocalDate finalHoldoutEnd
) {
    public WalkForwardReport {
        metrics = Map.copyOf(metrics);
    }

    /** 读取指定模型的指标；找不到时抛出包含模型名称的中文异常。 */
    public ForecastMetrics metric(ModelType type) {
        ForecastMetrics m = metrics.get(type);
        if (m == null) {
            throw new IllegalArgumentException("未找到模型指标：" + type);
        }
        return m;
    }
}
