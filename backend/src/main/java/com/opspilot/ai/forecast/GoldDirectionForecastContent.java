package com.opspilot.ai.forecast;

import java.util.List;

/** 保存模型生成并等待安全校验的结构化方向预测。 */
public record GoldDirectionForecastContent(
        ForecastDirection direction,
        String reasoning,
        List<String> invalidationConditions
) {
}
