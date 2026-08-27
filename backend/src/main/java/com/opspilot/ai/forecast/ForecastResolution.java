package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 保存一次真实价格解析产生的确定性评分字段。 */
public record ForecastResolution(
        LocalDate targetDate,
        BigDecimal targetPrice,
        BigDecimal actualReturn,
        ForecastDirection actualDirection,
        boolean hit,
        OffsetDateTime resolvedAt
) {
}
