package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证黄金预测输入数据的新鲜度边界和未来日期保护。 */
class GoldForecastDataFreshnessPolicyTests {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T12:00:00Z"),
            ZoneOffset.UTC
    );
    private final GoldForecastDataFreshnessPolicy policy =
            new GoldForecastDataFreshnessPolicy(CLOCK);

    @Test
    @DisplayName("黄金三天和宏观七天边界内允许生成预测")
    void acceptsBoundaryDates() {
        assertThatCode(() -> policy.validate(snapshot(
                "2026-08-24",
                "2026-08-20",
                "2026-08-20"
        ))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("黄金价格超过三天时拒绝生成预测")
    void rejectsStaleGoldPrice() {
        assertStale(
                snapshot("2026-08-23", "2026-08-25", "2026-08-21"),
                "黄金价格"
        );
    }

    @Test
    @DisplayName("任一宏观数据超过七天时拒绝生成预测")
    void rejectsStaleMacroData() {
        assertStale(
                snapshot("2026-08-26", "2026-08-19", "2026-08-21"),
                "实际利率"
        );
        assertStale(
                snapshot("2026-08-26", "2026-08-25", "2026-08-19"),
                "美元指数"
        );
    }

    @Test
    @DisplayName("任一观测日期来自未来时拒绝生成预测")
    void rejectsFutureObservationDate() {
        assertThatThrownBy(() -> policy.validate(snapshot(
                "2026-08-28",
                "2026-08-25",
                "2026-08-21"
        )))
                .isInstanceOf(StaleGoldForecastDataException.class)
                .hasMessageContaining("黄金价格")
                .hasMessageContaining("来自未来");
    }

    private void assertStale(
            GoldResearchSnapshot snapshot,
            String dataName
    ) {
        assertThatThrownBy(() -> policy.validate(snapshot))
                .isInstanceOf(StaleGoldForecastDataException.class)
                .hasMessageContaining(dataName)
                .hasMessageContaining("已过期");
    }

    private GoldResearchSnapshot snapshot(
            String goldDate,
            String realRateDate,
            String dollarIndexDate
    ) {
        StoredGoldResearchSnapshot source =
                GoldForecastTestFixtures.snapshot("2515.75");
        GoldResearchSnapshot value = source.snapshot();

        return new GoldResearchSnapshot(
                value.analysisDate(),
                LocalDate.parse(goldDate),
                LocalDate.parse(realRateDate),
                LocalDate.parse(dollarIndexDate),
                value.gold(), value.realRate(), value.dollarIndex(),
                value.realRateAssessment(),
                value.dollarIndexAssessment(),
                value.researchVersion(), value.disclaimer()
        );
    }
}
