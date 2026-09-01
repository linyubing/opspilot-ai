package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.ForecastStatus;
import com.opspilot.ai.forecast.GoldForecastMissReason;
import com.opspilot.ai.forecast.GoldTradingCalendar;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 对外返回黄金方向预测和结算字段，不暴露提示词和模型原始响应。 */
public record GoldForecastResponse(
        UUID id, UUID snapshotId, LocalDate baseDate, BigDecimal basePrice,
        ForecastDirection predictedDirection, String reasoning,
        List<String> invalidationConditions, String modelName,
        String promptVersion, String promptHash, String forecastRuleVersion,
        ForecastStatus status, String expectedTargetDate,
        LocalDate targetDate, BigDecimal targetPrice,
        BigDecimal actualReturn, ForecastDirection actualDirection,
        Boolean hit, GoldForecastMissReason missReason,
        OffsetDateTime resolvedAt, OffsetDateTime createdAt
) {
    public static GoldForecastResponse from(StoredGoldDirectionForecast record) {
        return from(record, null);
    }

    public static GoldForecastResponse from(
            StoredGoldDirectionForecast record, GoldForecastMissReason missReason
    ) {
        return from(record, missReason, null);
    }

    public static GoldForecastResponse from(
            StoredGoldDirectionForecast record,
            GoldForecastMissReason missReason,
            GoldTradingCalendar calendar
    ) {
        LocalDate expectedTarget = calendar != null
                ? calendar.nextBusinessDay(record.baseDate())
                : nextWeekday(record.baseDate());
        return new GoldForecastResponse(
                record.id(), record.snapshotId(), record.baseDate(), record.basePrice(),
                record.predictedDirection(), record.reasoning(), record.invalidationConditions(),
                record.modelName(), record.promptVersion(), record.promptHash(),
                record.forecastRuleVersion(), record.status(), expectedTarget.toString(),
                record.targetDate(), record.targetPrice(), record.actualReturn(),
                record.actualDirection(), record.hit(), missReason,
                record.resolvedAt(), record.createdAt()
        );
    }

    private static LocalDate nextWeekday(LocalDate baseDate) {
        LocalDate date = baseDate.plusDays(1);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }
}
