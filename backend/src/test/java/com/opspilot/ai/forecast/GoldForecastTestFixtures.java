package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;

/** 为黄金预测测试提供完整但不会写入生产库的固定领域对象。 */
final class GoldForecastTestFixtures {

    static final UUID SNAPSHOT_ID = UUID.fromString(
            "0da5c4c6-81e0-47e8-b016-b9c070830946"
    );

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-08-27T07:30:01Z");

    private GoldForecastTestFixtures() {
    }

    static StoredGoldResearchSnapshot snapshot(String goldPrice) {
        return new StoredGoldResearchSnapshot(
                SNAPSHOT_ID,
                new GoldResearchSnapshot(
                        LocalDate.parse("2026-08-21"),
                        LocalDate.parse("2026-08-26"),
                        LocalDate.parse("2026-08-25"),
                        LocalDate.parse("2026-08-21"),
                        new GoldReturnMetrics(
                                new BigDecimal(goldPrice),
                                new BigDecimal("0.1313"),
                                new BigDecimal("3.8413"),
                                new BigDecimal("11.7766"),
                                CREATED_AT
                        ),
                        new RealRateChangeMetrics(
                                new BigDecimal("2.400000"),
                                new BigDecimal("0.050000"),
                                new BigDecimal("-0.010000"),
                                new BigDecimal("-0.030000"),
                                new BigDecimal("5.00"),
                                new BigDecimal("-1.00"),
                                new BigDecimal("-3.00"),
                                CREATED_AT
                        ),
                        new DollarIndexChangeMetrics(
                                new BigDecimal("118.062800"),
                                new BigDecimal("-0.1624"),
                                new BigDecimal("-0.7065"),
                                new BigDecimal("-2.1934"),
                                CREATED_AT
                        ),
                        new ResearchFactorAssessment(
                                GoldFactorStatus.NEUTRAL,
                                "gold-real-rate-v1",
                                "实际利率变化有限。"
                        ),
                        new ResearchFactorAssessment(
                                GoldFactorStatus.SUPPORTIVE,
                                "gold-dollar-index-v1",
                                "美元指数走弱。"
                        ),
                        "gold-multifactor-v2",
                        "不构成投资建议。"
                ),
                CREATED_AT
        );
    }
}
