package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.RealRateFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcGoldResearchSnapshotRepositoryTests {

    private static final String RULE_VERSION =
            "gold-real-rate-history-repository-test-v1";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(
            2026,
            8,
            27,
            1,
            0,
            0,
            0,
            ZoneOffset.UTC
    );

    @Autowired
    private GoldResearchSnapshotRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTestData() {
        jdbcTemplate.update(
                "delete from gold_research_snapshot where rule_version = ?",
                RULE_VERSION
        );
    }

    @Test
    @DisplayName("首次保存创建正式快照")
    void createsSnapshot() {
        SaveGoldResearchSnapshotResult result =
                repository.saveIfAbsent(
                        snapshot("2026-08-24", "2500.00"),
                        CREATED_AT
                );

        assertThat(result.created()).isTrue();
        assertThat(result.record().id()).isNotNull();
        assertThat(result.record().snapshot().gold().currentPrice())
                .isEqualByComparingTo("2500.00");
        assertThat(result.record().createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("重复保存不覆盖第一次的历史数据")
    void keepsFirstSnapshotForSameKey() {
        repository.saveIfAbsent(
                snapshot("2026-08-24", "2500.00"),
                CREATED_AT
        );

        SaveGoldResearchSnapshotResult repeated =
                repository.saveIfAbsent(
                        snapshot("2026-08-24", "9999.00"),
                        CREATED_AT.plusHours(1)
                );

        assertThat(repeated.created()).isFalse();
        assertThat(repeated.record().snapshot().gold().currentPrice())
                .isEqualByComparingTo("2500.00");
        assertThat(repeated.record().createdAt())
                .isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("最近快照按分析日期倒序并限制数量")
    void findsRecentSnapshots() {
        save("2026-08-22");
        save("2026-08-25");
        save("2026-08-24");

        assertThat(repository.findRecent(2))
                .extracting(record ->
                        record.snapshot().analysisDate())
                .containsExactly(
                        LocalDate.parse("2026-08-25"),
                        LocalDate.parse("2026-08-24")
                );
    }

    @Test
    @DisplayName("查询数量不能小于一")
    void rejectsNonPositiveLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.findRecent(0))
                .withMessage("limit 必须在 1 到 100 之间");
    }

    @Test
    @DisplayName("查询数量不能超过一百")
    void rejectsExcessiveLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.findRecent(101))
                .withMessage("limit 必须在 1 到 100 之间");
    }

    private void save(String analysisDate) {
        repository.saveIfAbsent(
                snapshot(analysisDate, "2500.00"),
                CREATED_AT
        );
    }

    /**
     * 固定数值只验证不可变保存和对象映射，不代表真实行情。
     */
    private GoldResearchSnapshot snapshot(
            String analysisDate,
            String goldPrice
    ) {
        LocalDate date = LocalDate.parse(analysisDate);
        OffsetDateTime collectedAt = CREATED_AT.minusHours(1);

        return new GoldResearchSnapshot(
                date,
                date.plusDays(1),
                date,
                new GoldReturnMetrics(
                        new BigDecimal(goldPrice),
                        new BigDecimal("0.1000"),
                        new BigDecimal("1.2000"),
                        new BigDecimal("2.3000"),
                        collectedAt
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.380000"),
                        new BigDecimal("-0.020000"),
                        new BigDecimal("-0.060000"),
                        new BigDecimal("-0.060000"),
                        new BigDecimal("-2.00"),
                        new BigDecimal("-6.00"),
                        new BigDecimal("-6.00"),
                        collectedAt
                ),
                new ResearchFactorAssessment(
                        RealRateFactorStatus.NEUTRAL,
                        RULE_VERSION,
                        "实际利率变化有限，单因子状态为中性。"
                ),
                "单因子状态不构成投资建议。"
        );
    }
}
