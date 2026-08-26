package com.opspilot.ai.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record GoldReturnMetrics(
        BigDecimal currentPrice,
        BigDecimal return1,
        BigDecimal return5,
        BigDecimal return20,
        OffsetDateTime collectedAt
) {
}
