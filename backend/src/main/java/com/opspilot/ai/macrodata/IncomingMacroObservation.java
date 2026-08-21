package com.opspilot.ai.macrodata;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 外部数据源返回、尚未持久化的宏观观测。
 * 数据库 ID 和采集时间由持久化流程统一生成。
 */
public record IncomingMacroObservation(
        String seriesId,
        LocalDate observationDate,
        BigDecimal value,
        String unit,
        String provider
) {
    public IncomingMacroObservation {
        if (seriesId == null || seriesId.isBlank()) {
            throw new IllegalArgumentException(
                    "seriesId 不能为空"
            );
        }

        if (value == null) {
            throw new IllegalArgumentException(
                    "value 不能为空"
            );
        }
    }
}
