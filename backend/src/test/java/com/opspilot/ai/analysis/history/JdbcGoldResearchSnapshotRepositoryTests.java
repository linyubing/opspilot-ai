package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcGoldResearchSnapshotRepositoryTests {

    private static final String LEGACY_RESEARCH_VERSION =
            "gold-real-rate-history-repository-test-v1";
    private static final String RESEARCH_VERSION =
            "gold-multifactor-history-repository-test-v2";
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
                """
                delete from gold_research_snapshot
                where research_version in (?, ?)
                """,
                LEGACY_RESEARCH_VERSION,
                RESEARCH_VERSION
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
        assertThat(result.record().snapshot().dollarIndex().currentIndex())
                .isEqualByComparingTo("118.062800");
        assertThat(result.record().snapshot().dollarIndexAssessment().status())
                .isEqualTo(GoldFactorStatus.SUPPORTIVE);
        assertThat(result.record().snapshot().researchVersion())
                .isEqualTo(RESEARCH_VERSION);
        assertThat(result.record().createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("按照编号读取已经保存的正式快照")
    void findsSnapshotById() {
        SaveGoldResearchSnapshotResult saved = repository.saveIfAbsent(
                snapshot("2026-08-24", "2500.00"),
                CREATED_AT
        );

        assertThat(repository.findById(saved.record().id()))
                .contains(saved.record());
    }

    @Test
    @DisplayName("编号不存在时返回空结果")
    void returnsEmptyWhenSnapshotIdDoesNotExist() {
        assertThat(repository.findById(UUID.fromString(
                "99999999-9999-9999-9999-999999999999"
        ))).isEmpty();
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
    @DisplayName("旧单因子记录读取时美元指数对象保持为空")
    void readsLegacySnapshotWithoutDollarIndex() {
        insertLegacySnapshot();

        GoldResearchSnapshot snapshot = repository.findRecent(100).stream()
                .map(StoredGoldResearchSnapshot::snapshot)
                .filter(item -> LEGACY_RESEARCH_VERSION.equals(
                        item.researchVersion()
                ))
                .findFirst()
                .orElseThrow();

        assertThat(snapshot.realRateAssessment().ruleVersion())
                .isEqualTo(LEGACY_RESEARCH_VERSION);
        assertThat(snapshot.dollarIndex()).isNull();
        assertThat(snapshot.dollarIndexAssessment()).isNull();
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
                new DollarIndexChangeMetrics(
                        new BigDecimal("118.062800"),
                        new BigDecimal("-0.1000"),
                        new BigDecimal("-0.6000"),
                        new BigDecimal("-1.2000"),
                        collectedAt
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "gold-real-rate-v1",
                        "实际利率变化有限，单因子状态为中性。"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE,
                        "gold-dollar-index-v1",
                        "广义美元指数持续走弱，对黄金构成单因子支撑。"
                ),
                RESEARCH_VERSION,
                "双因子状态不构成投资建议。"
        );
    }

    /** 模拟 V4 已存在的单因子历史行，验证升级后的兼容读取。 */
    private void insertLegacySnapshot() {
        jdbcTemplate.update("""
                insert into gold_research_snapshot (
                    id,
                    analysis_date,
                    latest_gold_date,
                    latest_real_rate_date,
                    gold_price,
                    gold_return_1,
                    gold_return_5,
                    gold_return_20,
                    gold_collected_at,
                    real_rate,
                    real_rate_change_1,
                    real_rate_change_5,
                    real_rate_change_20,
                    real_rate_collected_at,
                    real_rate_status,
                    real_rate_rule_version,
                    real_rate_explanation,
                    research_version,
                    disclaimer,
                    created_at
                )
                values (
                    gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                LocalDate.parse("2026-08-20"),
                LocalDate.parse("2026-08-21"),
                LocalDate.parse("2026-08-20"),
                new BigDecimal("2450.00"),
                new BigDecimal("0.1000"),
                new BigDecimal("1.2000"),
                new BigDecimal("2.3000"),
                CREATED_AT.minusHours(1),
                new BigDecimal("2.380000"),
                new BigDecimal("-0.020000"),
                new BigDecimal("-0.060000"),
                new BigDecimal("-0.060000"),
                CREATED_AT.minusHours(1),
                "neutral",
                LEGACY_RESEARCH_VERSION,
                "旧版实际利率单因子状态为中性。",
                LEGACY_RESEARCH_VERSION,
                "旧版单因子状态不构成投资建议。",
                CREATED_AT
        );
    }
}
