package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.macrodata.MacroObservation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 对外返回的实际利率观测，不暴露数据库内部版本字段。
 */
public record RealRateResponse(
        String seriesId,
        LocalDate observationDate,
        BigDecimal value,
        String unit,
        String provider,
        OffsetDateTime collectedAt
) {

    public static RealRateResponse from(MacroObservation observation) {
        return new RealRateResponse(
                observation.seriesId(),
                observation.observationDate(),
                observation.value(),
                observation.unit(),
                observation.provider(),
                observation.collectedAt()
        );
    }
}
