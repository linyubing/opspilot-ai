package com.opspilot.ai.forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import com.fasterxml.jackson.annotation.JsonFormat;

/** 保存预测承诺的候选目标时段，不是官方交易时段证明。 */
public record GoldForecastTiming(LocalDate targetDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant start,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant end, String ruleVersion) {
    public GoldForecastTiming {
        if (targetDate == null || start == null || end == null
                || !GoldSessionCheckResult.RULE_VERSION.equals(ruleVersion)) {
            throw new IllegalArgumentException("预测候选时段字段或版本无效");
        }
        var expected = GoldSession.forDate(targetDate);
        if (!start.equals(expected.start()) || !end.equals(expected.end())) {
            throw new IllegalArgumentException("预测候选时段与承诺标签不一致");
        }
    }

    /** 只描述发布时间位置，不将候选核验升级为可信预测。 */
    public Phase phase(OffsetDateTime createdAt) {
        if (createdAt == null) return Phase.UNKNOWN;
        Instant time = createdAt.toInstant();
        if (time.isBefore(start)) return Phase.BEFORE_SESSION;
        if (time.isBefore(end)) return Phase.IN_SESSION;
        return Phase.EXPIRED;
    }

    public enum Phase { UNKNOWN, BEFORE_SESSION, IN_SESSION, EXPIRED }

    /** 候选结算的时间资格；通过仍不是官方时段或历史PIT认证。 */
    public boolean canResolve(LocalDate baseDate, LocalDate actualTarget,
            OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        return baseDate != null && targetDate.isAfter(baseDate) && targetDate.equals(actualTarget)
                && createdAt != null && resolvedAt != null
                && !createdAt.toInstant().isBefore(GoldSession.forDate(baseDate).end())
                && createdAt.toInstant().isBefore(end)
                && !resolvedAt.toInstant().isBefore(end)
                && !resolvedAt.toInstant().isBefore(createdAt.toInstant());
    }
}
