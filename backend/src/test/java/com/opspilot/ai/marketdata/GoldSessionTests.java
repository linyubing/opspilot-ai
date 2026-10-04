package com.opspilot.ai.marketdata;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GoldSessionTests {
    // 时间字面量独立于生产计算，防止固定24小时掩盖夏令时。
    @ParameterizedTest
    @CsvSource({
        "2026-01-15,2026-01-14T20:00:00Z,2026-01-15T20:00:00Z,24",
        "2026-04-04,2026-04-03T20:00:00Z,2026-04-04T21:00:00Z,25",
        "2026-10-03,2026-10-02T21:00:00Z,2026-10-03T20:00:00Z,23"
    })
    void followsSydneyClock(String date, String start, String end, long count) {
        GoldSession result = GoldSession.forDate(LocalDate.parse(date));
        assertThat(result).isNotNull();
        assertThat(result.date()).isEqualTo(LocalDate.parse(date));
        assertThat(result.start()).isEqualTo(Instant.parse(start));
        assertThat(result.end()).isEqualTo(Instant.parse(end));
        assertThat(Duration.between(result.start(), result.end()).toHours()).isEqualTo(count);
    }

    @Test
    void rejectsNullDate() {
        assertThatIllegalArgumentException().isThrownBy(() -> GoldSession.forDate(null));
    }
}
