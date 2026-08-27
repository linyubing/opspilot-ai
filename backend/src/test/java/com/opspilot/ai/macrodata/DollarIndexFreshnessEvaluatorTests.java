package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DollarIndexFreshnessEvaluatorTests {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T01:00:00Z"),
            ZoneOffset.UTC
    );
    private final DollarIndexFreshnessEvaluator evaluator =
            new DollarIndexFreshnessEvaluator(CLOCK);

    @Test
    @DisplayName("七个自然日边界内仍视为当前数据")
    void treatsSevenDaysOldAsCurrent() {
        assertThat(evaluator.evaluate(LocalDate.parse("2026-08-20")))
                .isEqualTo(DollarIndexFreshness.CURRENT);
    }

    @Test
    @DisplayName("超过七个自然日视为陈旧数据")
    void treatsEightDaysOldAsStale() {
        assertThat(evaluator.evaluate(LocalDate.parse("2026-08-19")))
                .isEqualTo(DollarIndexFreshness.STALE);
    }

    @Test
    @DisplayName("拒绝来自未来日期的观测")
    void rejectsFutureObservationDate() {
        assertThatThrownBy(() -> evaluator.evaluate(
                LocalDate.parse("2026-08-28")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
