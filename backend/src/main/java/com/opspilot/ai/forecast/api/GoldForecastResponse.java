package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.ForecastStatus;
import com.opspilot.ai.forecast.GoldForecastMissReason;
import com.opspilot.ai.forecast.GoldTradingCalendar;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import com.opspilot.ai.forecast.GoldForecastTiming;

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
        OffsetDateTime resolvedAt, OffsetDateTime createdAt,
        GoldForecastTiming timing, GoldForecastTiming.Phase publicationPhase,
        String publicationWarning
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
        LocalDate expectedTarget = record.timing() != null ? record.timing().targetDate() : calendar != null
                ? calendar.nextBusinessDay(record.baseDate())
                : nextWeekday(record.baseDate());
        var phase = record.timing() == null ? GoldForecastTiming.Phase.UNKNOWN
                : record.timing().phase(record.createdAt());
        return new GoldForecastResponse(
                record.id(), record.snapshotId(), record.baseDate(), record.basePrice(),
                record.predictedDirection(), record.reasoning(), record.invalidationConditions(),
                record.modelName(), record.promptVersion(), record.promptHash(),
                record.forecastRuleVersion(), record.status(), expectedTarget.toString(),
                record.targetDate(), record.targetPrice(), record.actualReturn(),
                record.actualDirection(), record.hit(), missReason,
                record.resolvedAt(), record.createdAt(), record.timing(), phase, warning(phase)
        );
    }

    private static String warning(GoldForecastTiming.Phase phase) {
        return switch (phase) {
            case UNKNOWN -> "发布时间合同未知，旧记录不能证明事前预测，不计为可信预测样本。";
            case IN_SESSION -> "目标候选时段已经开始，不能视为开盘前预测；候选依据不是官方时段或历史可得性证明。";
            case BEFORE_SESSION -> "候选时段开盘前发布；候选依据不能视为官方时段或历史可得性证明。";
            case EXPIRED -> "发布时目标候选时段已结束，不能计为事前预测。";
        };
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
