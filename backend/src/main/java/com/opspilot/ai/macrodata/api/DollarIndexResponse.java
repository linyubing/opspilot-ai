package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.macrodata.DollarIndexFreshness;
import com.opspilot.ai.macrodata.MacroObservation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 对外返回广义美元指数观测及其新鲜度。 */
public record DollarIndexResponse(
        String seriesId,
        LocalDate observationDate,
        BigDecimal value,
        String unit,
        String provider,
        OffsetDateTime collectedAt,
        DollarIndexFreshness freshness
) {
    public static DollarIndexResponse from(
            MacroObservation observation,
            DollarIndexFreshness freshness
    ) {
        return new DollarIndexResponse(
                observation.seriesId(),
                observation.observationDate(),
                observation.value(),
                observation.unit(),
                observation.provider(),
                observation.collectedAt(),
                freshness
        );
    }
}
