package com.opspilot.ai.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 保存广义美元指数当前值及不同周期的百分比变化。 */
public record DollarIndexChangeMetrics(
        BigDecimal currentIndex,
        BigDecimal return1,
        BigDecimal return5,
        BigDecimal return20,
        OffsetDateTime collectedAt
) {
}
