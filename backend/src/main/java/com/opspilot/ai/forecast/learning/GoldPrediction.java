package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;

import java.util.Objects;

/** 保存黄金模型最终采用的方向或证据不足状态。 */
public record GoldPrediction(
        SignalStatus status,
        ForecastDirection direction,
        double confidence
) {
    public GoldPrediction {
        Objects.requireNonNull(status, "信号状态不能为空");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("置信度必须处于 0 到 1 之间");
        }
        if (status == SignalStatus.NO_SIGNAL && direction != null) {
            throw new IllegalArgumentException("证据不足时不能保存正式方向");
        }
        if (status == SignalStatus.PREDICTED && direction == null) {
            throw new IllegalArgumentException("正式预测必须包含方向");
        }
    }
}
