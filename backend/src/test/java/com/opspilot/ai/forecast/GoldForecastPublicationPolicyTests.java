package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅用人工数学样例核验发布边界，不写真实行情或调用模型。 */
class GoldForecastPublicationPolicyTests {
    private final GoldDailyBarRepository repository = mock(GoldDailyBarRepository.class);
    private final ConfiguredGoldTradingCalendar calendar = new ConfiguredGoldTradingCalendar();
    private final GoldForecastPublicationPolicy policy = new GoldForecastPublicationPolicy(calendar, repository);
    private final LocalDate base = LocalDate.parse("2026-08-26");

    @Test @DisplayName("基准候选日线未结束时，不能把完整收盘当作已知输入")
    void rejectsUnendedBase() {
        assertThatThrownBy(() -> policy.check(base, policy.plan(base), clockAt("2026-08-26T20:59:59Z")))
                .isInstanceOf(InvalidGoldPublicationException.class).hasMessageContaining("基准");
        verifyNoInteractions(repository);
    }

    @Test @DisplayName("有效确认的后续行情已知时，不再生成未来预测")
    void rejectsKnownTarget() {
        when(repository.findNext("XAUUSD", "twelve_data", base))
                .thenReturn(Optional.of(bar("2026-08-27", true)));
        assertThatThrownBy(() -> policy.check(base, policy.plan(base), clockAt("2026-08-27T12:00:00Z")))
                .isInstanceOf(InvalidGoldPublicationException.class).hasMessageContaining("已知");
    }

    @Test @DisplayName("未确认行不冒充已知收盘，但仍须遵守候选截止线")
    void allowsUnknownTarget() {
        when(repository.findNext("XAUUSD", "twelve_data", base))
                .thenReturn(Optional.of(bar("2026-08-27", false)));
        assertThatCode(() -> policy.check(base, policy.plan(base), clockAt("2026-08-27T12:00:00Z")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check(base, policy.plan(base), clockAt("2026-08-27T21:00:00Z")))
                .isInstanceOf(InvalidGoldPublicationException.class);
    }

    @Test @DisplayName("同一绝对时刻在不同时区偏移下具有相同截止结果")
    void comparesInstants() {
        var timing = policy.plan(base);
        for (String now : new String[]{"2026-08-27T21:00:00Z", "2026-08-28T05:00:00+08:00"}) {
            assertThatThrownBy(() -> policy.check(base, timing, clockAt(now)))
                    .isInstanceOf(InvalidGoldPublicationException.class);
        }
        assertThatCode(() -> policy.check(base, timing, clockAt("2026-08-28T04:59:59+08:00")))
                .doesNotThrowAnyException();
    }

    @Test @DisplayName("周末及配置休市日跳过，但候选两端点分别处理夏令时")
    void freezesCalendarTarget() {
        var weekend = policy.plan(LocalDate.parse("2026-10-02"));
        assertThat(weekend.targetDate()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(weekend.start()).isEqualTo(Instant.parse("2026-10-04T20:00:00Z"));
        assertThat(weekend.end()).isEqualTo(Instant.parse("2026-10-05T20:00:00Z"));
        calendar.setHolidays(Set.of("--10-05"));
        assertThat(policy.plan(LocalDate.parse("2026-10-02")).targetDate())
                .isEqualTo(LocalDate.parse("2026-10-06"));
        // 已冻结对象不因日历配置改变而改目标。
        assertThat(weekend.targetDate()).isEqualTo(LocalDate.parse("2026-10-05"));
    }

    @Test @DisplayName("错误或缺失规则不能构造看似有效的发布时间合同")
    void rejectsInvalidTiming() {
        var good = policy.plan(base);
        assertThatThrownBy(() -> new GoldForecastTiming(good.targetDate(), good.start(), good.end(), "official"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoldForecastTiming(good.targetDate(), good.start().plusSeconds(1), good.end(), good.ruleVersion()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoldForecastTiming(null, good.start(), good.end(), good.ruleVersion()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("不晚于基准的承诺目标不能进入验证")
    void rejectsEarlierTarget() {
        var earlier = policy.plan(LocalDate.parse("2026-08-25"));
        assertThatThrownBy(() -> policy.check(base, earlier, clockAt("2026-08-26T12:00:00Z")))
                .isInstanceOf(InvalidGoldPublicationException.class);
    }

    @Test @DisplayName("恰好开盘属于盘中，恰好结束属于过期，未知时间不猜测")
    void reportsPublicationPhase() {
        var timing = policy.plan(base);
        assertThat(phase(timing, at("2026-08-26T20:59:59Z"))).isEqualTo("BEFORE_SESSION");
        assertThat(phase(timing, at("2026-08-26T21:00:00Z"))).isEqualTo("IN_SESSION");
        assertThat(phase(timing, at("2026-08-28T05:00:00+08:00"))).isEqualTo("EXPIRED");
        assertThat(phase(timing, null)).isEqualTo("UNKNOWN");
    }

    private String phase(GoldForecastTiming timing, OffsetDateTime date) {
        return timing.phase(date).name();
    }

    private OffsetDateTime at(String value) { return OffsetDateTime.parse(value); }
    private java.time.Clock clockAt(String value) {
        return java.time.Clock.fixed(at(value).toInstant(), java.time.ZoneOffset.UTC);
    }

    private GoldDailyBar bar(String date, boolean confirmed) {
        var now = at("2026-08-27T10:00:00Z");
        return new GoldDailyBar("XAUUSD", LocalDate.parse(date), BigDecimal.TEN,
                new BigDecimal("12"), new BigDecimal("8"), new BigDecimal("11"),
                "usd", "troy_ounce", "twelve_data", now, confirmed
                ? new GoldBarConfirmation(GoldBarConfirmation.SOURCE, LocalDate.parse(date), now, "a".repeat(64)) : null);
    }
}
