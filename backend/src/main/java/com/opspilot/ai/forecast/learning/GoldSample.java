package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;

import java.time.LocalDate;
import java.util.Objects;

/** 保存一个可审计的黄金监督学习样本。 */
public record GoldSample(
        LocalDate asOfDate,
        LocalDate targetDate,
        ForecastHorizon horizon,
        GoldFeatures features,
        ForecastDirection label
) {
    public GoldSample {
        Objects.requireNonNull(asOfDate, "分析日期不能为空");
        Objects.requireNonNull(targetDate, "目标日期不能为空");
        Objects.requireNonNull(horizon, "预测周期不能为空");
        Objects.requireNonNull(features, "黄金特征不能为空");
        Objects.requireNonNull(label, "真实方向不能为空");
        if (!targetDate.isAfter(asOfDate)) {
            throw new IllegalArgumentException("目标日期必须晚于分析日期");
        }
    }
}
