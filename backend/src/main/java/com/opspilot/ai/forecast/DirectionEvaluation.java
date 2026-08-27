package com.opspilot.ai.forecast;

import java.math.BigDecimal;

/** 保存某个预测方向的已验证样本数、命中数和准确率。 */
public record DirectionEvaluation(
        ForecastDirection direction,
        int sampleCount,
        int hitCount,
        BigDecimal accuracy
) {
}
