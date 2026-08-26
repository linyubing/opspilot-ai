package com.opspilot.ai.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 保存实际利率当前值及不同周期的百分点和基点变化。
 */
public record RealRateChangeMetrics(
        BigDecimal currentRate,
        BigDecimal percentagePointChange1,
        BigDecimal percentagePointChange5,
        BigDecimal percentagePointChange20,
        BigDecimal basisPointChange1,
        BigDecimal basisPointChange5,
        BigDecimal basisPointChange20,
        OffsetDateTime collectedAt
) {
}
