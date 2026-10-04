package com.opspilot.ai.marketdata;

import java.time.Instant;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import static com.opspilot.ai.marketdata.GoldSessionCheckResult.Status.*;

/** 纯时间与价格核验，不访问行情、仓储或预测模型。 */
@Component
public class GoldSessionCheck {
    public GoldSessionCheckResult check(GoldDailyBar day,
            List<GoldHourBar> hours, Instant checkedAt) {
        if (day == null || hours == null || checkedAt == null) {
            throw new IllegalArgumentException("黄金时段核验参数不能为空");
        }
        GoldSession session = day.priceDate() == null ? null : GoldSession.forDate(day.priceDate());
        int expected = session == null ? 0 : Math.toIntExact(Duration.between(session.start(), session.end()).toHours());
        if (!"XAUUSD".equals(day.symbol()) || !"twelve_data".equals(day.provider())
                || !"usd".equals(day.currency()) || !"troy_ounce".equals(day.unit())
                || session == null || day.collectedAt() == null || day.collectedAt().toInstant().isAfter(checkedAt)
                || !GoldHourBar.validPrices(day.open(), day.high(), day.low(), day.close())) {
            return result(session, checkedAt, INVALID_INPUT, expected, hours.size(), List.of(), "原生日线标识、时间或价格无效");
        }
        if (checkedAt.isBefore(session.end())) {
            return result(session, checkedAt, NOT_ENDED, expected, hours.size(), List.of(), "候选时段尚未结束");
        }
        var seen = new HashSet<Instant>();
        for (GoldHourBar bar : hours) {
            if (bar == null || bar.start().getNano() != 0 || bar.start().getEpochSecond() % 3600 != 0
                    || bar.start().isBefore(session.start()) || !bar.start().isBefore(session.end())
                    || !seen.add(bar.start())) {
                return result(session, checkedAt, INVALID_INPUT, expected, hours.size(), List.of(), "小时线包含空行、非整点、越界或重复时间");
            }
        }
        var missing = new ArrayList<Instant>();
        for (Instant time = session.start(); time.isBefore(session.end()); time = time.plusSeconds(3600)) {
            if (!seen.contains(time)) missing.add(time);
        }
        if (!missing.isEmpty()) {
            return result(session, checkedAt, MISSING_HOURS, expected, hours.size(), missing, "候选时段缺少小时线，不能核验价格一致性");
        }
        var sorted = hours.stream().sorted(Comparator.comparing(GoldHourBar::start)).toList();
        BigDecimal high = sorted.stream().map(GoldHourBar::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = sorted.stream().map(GoldHourBar::low).min(BigDecimal::compareTo).orElseThrow();
        if (day.open().compareTo(sorted.getFirst().open()) != 0 || day.close().compareTo(sorted.getLast().close()) != 0
                || day.high().compareTo(high) != 0 || day.low().compareTo(low) != 0) {
            return result(session, checkedAt, OHLC_MISMATCH, expected, hours.size(), List.of(), "完整小时线聚合与原生日线开高低收不一致");
        }
        return result(session, checkedAt, MATCHED, expected, hours.size(), List.of(), "候选时段完整，开高低收精确一致");
    }

    private GoldSessionCheckResult result(GoldSession session, Instant checkedAt,
            GoldSessionCheckResult.Status status, int expected, int received,
            List<Instant> missing, String reason) {
        return new GoldSessionCheckResult(session, checkedAt, status, expected, received, missing, reason);
    }
}
