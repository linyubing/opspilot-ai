package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 使用候选时段守住发布时间，候选依据不能升级为官方确认。 */
@Component
public class GoldForecastPublicationPolicy {
    private final GoldTradingCalendar calendar;
    private final GoldDailyBarRepository repository;

    public GoldForecastPublicationPolicy(GoldTradingCalendar calendar, GoldDailyBarRepository repository) {
        this.calendar = calendar;
        this.repository = repository;
    }

    public GoldForecastTiming plan(LocalDate baseDate) {
        if (baseDate == null) throw new InvalidGoldPublicationException("基准日期不能为空");
        LocalDate targetDate = calendar.nextBusinessDay(baseDate);
        if (targetDate == null || !targetDate.isAfter(baseDate)) {
            throw new InvalidGoldPublicationException("承诺目标必须晚于基准日期");
        }
        var session = GoldSession.forDate(targetDate);
        return new GoldForecastTiming(session.date(), session.start(), session.end(),
                GoldSessionCheckResult.RULE_VERSION);
    }

    public void validate(LocalDate baseDate, GoldForecastTiming timing, OffsetDateTime asOf) {
        if (baseDate == null || timing == null || asOf == null
                || !timing.targetDate().isAfter(baseDate)) {
            throw new InvalidGoldPublicationException("预测发布时间或承诺目标无效");
        }
        if (asOf.toInstant().isBefore(GoldSession.forDate(baseDate).end())) {
            throw new InvalidGoldPublicationException("基准候选时段尚未结束，不能使用完整收盘输入");
        }
        if (!asOf.toInstant().isBefore(timing.end())) {
            throw new InvalidGoldPublicationException("目标候选时段已结束，不能新建未来预测");
        }
        if (repository.findNext("XAUUSD", "twelve_data", baseDate)
                .filter(bar -> bar.isConfirmedAt(asOf)).isPresent()) {
            throw new InvalidGoldPublicationException("后续目标行情已经确认并已知，不能新建未来预测");
        }
    }
}
