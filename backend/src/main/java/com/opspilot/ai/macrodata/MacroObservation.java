package com.opspilot.ai.macrodata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已持久化的宏观观测版本。
 * supersededAt 为空表示它是该观测日期的当前版本。
 */
public record MacroObservation(
        UUID id,
        String seriesId,
        LocalDate observationDate,
        BigDecimal value,
        String unit,
        String provider,
        OffsetDateTime collectedAt,
        OffsetDateTime supersededAt
) {
}
