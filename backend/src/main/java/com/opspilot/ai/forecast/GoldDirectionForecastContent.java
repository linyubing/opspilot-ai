package com.opspilot.ai.forecast;

import java.util.List;

/** 保存模型生成并等待安全校验的结构化方向预测。 */
public record GoldDirectionForecastContent(
        ForecastDirection direction,
        String reasoning,
        List<String> invalidationConditions,
        ForecastSignal signal
) {
    /** 旧研究输出未声明信号协议，不能事后补成显式预测。 */
    public GoldDirectionForecastContent(ForecastDirection direction, String reasoning,
            List<String> invalidationConditions) {
        this(direction, reasoning, invalidationConditions, null);
    }
}
