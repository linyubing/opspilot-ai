package com.opspilot.ai.macrodata;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 表示 FRED 序列中的一条有效日期和值。 */
public record FredSeriesObservation(
        LocalDate observationDate,
        BigDecimal value
) {
}
