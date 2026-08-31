package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;

import java.time.LocalDate;
import java.util.Objects;

/** 保存一条已经获得真实方向的概率预测。 */
public record SettledPrediction(
        LocalDate asOfDate,
        DirectionProbabilities probabilities,
        GoldPrediction prediction,
        ForecastDirection actual
) {
    public SettledPrediction {
        Objects.requireNonNull(asOfDate, "分析日期不能为空");
        Objects.requireNonNull(probabilities, "方向概率不能为空");
        Objects.requireNonNull(prediction, "黄金预测不能为空");
        Objects.requireNonNull(actual, "真实方向不能为空");
    }
}
