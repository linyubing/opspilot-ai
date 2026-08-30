package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.ForecastDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 保存波动区间诊断所需的一条历史预测样本。 */
public record VolatilitySample(
        LocalDate date,
        BigDecimal volatility,
        ForecastDirection predicted,
        ForecastDirection actual
) {
}
