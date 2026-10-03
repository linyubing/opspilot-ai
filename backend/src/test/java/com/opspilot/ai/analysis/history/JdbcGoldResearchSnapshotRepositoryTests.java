package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.GoldSnapshotFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private ObjectMapper json;

    private final List<UUID> inputIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTestData() {
        for (UUID id : inputIds) jdbcTemplate.update("delete from gold_research_snapshot where id = ?", id);
        inputIds.clear();
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
    @DisplayName("数据库拒绝部分或JSON null依据，不让SQL空值逻辑绕过结构约束")
    void rejectsPartialInput() {
        UUID id = repository.saveIfAbsent(snapshot("2026-08-24", "2500.00"), CREATED_AT).record().id();
        for (String invalid : List.of("{}", "[]", "null", "{\"bars\":[],\"checkedAt\":\"x\"}")) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "update gold_research_snapshot set gold_input = ?::jsonb where id = ?", invalid, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(repository.findById(id).orElseThrow().snapshot().input()).isNull();
    }

    @Test
    @DisplayName("只有新版本名但没有依据的数据库记录不能通过约束")
    void rejectsVersionWithoutInput() {
        UUID id = repository.saveIfAbsent(snapshot("2026-08-24", "2500.00"), CREATED_AT).record().id();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update gold_research_snapshot set research_version = ? where id = ?",
                "gold-multifactor-confirmed-v3", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findById(id).orElseThrow().snapshot().researchVersion()).isEqualTo(RESEARCH_VERSION);
    }

    @Test
    @DisplayName("同日旧v2与新确认v3各自保存，重复保存不覆盖第一份依据")
    void keepsLegacyAndConfirmedInputs() {
        // 隔离到不存在真实黄金记录的早期日期，按精确UUID清理。
        LocalDate date = LocalDate.of(1000, 1, 1)
                .plusDays(UUID.randomUUID().getLeastSignificantBits() & 65535);
        GoldResearchSnapshot source = snapshot(date.toString(), "2500.00");
        GoldResearchSnapshot old = new GoldResearchSnapshot(date, date, source.latestRealRateDate(),
                source.latestDollarIndexDate(), source.gold(), source.realRate(), source.dollarIndex(),
                source.realRateAssessment(), source.dollarIndexAssessment(), "gold-multifactor-v2", source.disclaimer());
        StoredGoldResearchSnapshot legacy = repository.saveIfAbsent(old, CREATED_AT).record();
        inputIds.add(legacy.id());
        GoldResearchSnapshot value = GoldSnapshotFixtures.withInput(source, date,
                "gold-multifactor-confirmed-v3", CREATED_AT);
        StoredGoldResearchSnapshot saved = repository.saveIfAbsent(value, CREATED_AT).record();
        inputIds.add(saved.id());
        GoldResearchSnapshot changed = GoldSnapshotFixtures.withInput(source, date,
                "gold-multifactor-confirmed-v3", CREATED_AT.plusMinutes(1));
        SaveGoldResearchSnapshotResult repeated = repository.saveIfAbsent(changed, CREATED_AT.plusMinutes(1));

        assertThat(saved.id()).isNotEqualTo(legacy.id());
        assertThat(repository.findById(legacy.id()).orElseThrow().snapshot().input()).isNull();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.record().snapshot().input()).isEqualTo(value.input());
    }

    @Test
    @DisplayName("旧回测JSON缺input仍可反序列化，但不能自动补依据")
    void readsOldJson() throws Exception {
        ObjectNode value = json.valueToTree(snapshot("2026-08-24", "2500.00"));
        value.remove("input");

        GoldResearchSnapshot old = json.readValue(json.writeValueAsString(value), GoldResearchSnapshot.class);

        assertThat(old.input()).isNull();
        assertThat(old.gold().currentPrice()).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("数据库往返保留实际黄金窗口、逐根依据和核验时刻")
    void roundTripsGoldInput() {
        GoldResearchSnapshot value = GoldSnapshotFixtures.withInput(snapshot("2026-08-24", "2500.00"),
                LocalDate.parse("2026-08-24"), RESEARCH_VERSION, CREATED_AT);

        StoredGoldResearchSnapshot saved = repository.saveIfAbsent(value, CREATED_AT).record();
        GoldResearchSnapshot read = repository.findById(saved.id()).orElseThrow().snapshot();

        assertThat(read.input()).isNotNull();
        assertThat(read.input()).isEqualTo(value.input());
        assertThat(read.input().matches(read.latestGoldDate(), read.gold(), CREATED_AT)).isTrue();
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
        assertThat(result.record().snapshot().gold().volatility20())
                .isEqualByComparingTo("18.7500");
        assertThat(result.record().snapshot().dollarIndex().currentIndex())
                .isEqualByComparingTo("118.062800");
        assertThat(result.record().snapshot().dollarIndexAssessment().status())
                .isEqualTo(GoldFactorStatus.SUPPORTIVE);
        assertThat(result.record().snapshot().researchVersion())
                .isEqualTo(RESEARCH_VERSION);
        assertThat(result.record().createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("不能无损保存的九位价格小数在写库前被拒绝")
    void rejectsPriceLoss() {
        assertPrecisionRejected("2500.123456789", CREATED_AT.minusHours(1), CREATED_AT);
    }

    @Test
    @DisplayName("不能无损保存的纳秒采集时刻在写库前被拒绝")
    void rejectsCollectionLoss() {
        assertPrecisionRejected("2500.00", CREATED_AT.minusHours(1).withNano(123456400), CREATED_AT);
    }

    @Test
    @DisplayName("不能无损保存的纳秒保存时刻在写库前被拒绝")
    void rejectsCreationLoss() {
        assertPrecisionRejected("2500.00", CREATED_AT.minusHours(1), CREATED_AT.withNano(123456400));
    }

    private void assertPrecisionRejected(String price, OffsetDateTime collectedAt, OffsetDateTime createdAt) {
        GoldResearchSnapshot value = precisionInput(price, collectedAt, createdAt);
        assertThat(value.input().matches(value.latestGoldDate(), value.gold(), createdAt)).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> repository.saveIfAbsent(value, createdAt));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from gold_research_snapshot where research_version = ?",
                Integer.class, RESEARCH_VERSION)).isZero();
    }

    @Test
    @DisplayName("八位有效小数及微秒时间无损往返，尾随零不误拒绝")
    void keepsExactPrecision() {
        OffsetDateTime createdAt = CREATED_AT.withNano(123456000);
        GoldResearchSnapshot value = precisionInput("2500.1234567800",
                CREATED_AT.minusHours(1).withNano(654321000), createdAt);
        StoredGoldResearchSnapshot saved = repository.saveIfAbsent(value, createdAt).record();
        StoredGoldResearchSnapshot read = repository.findById(saved.id()).orElseThrow();
        assertThat(read.snapshot().gold().currentPrice()).isEqualByComparingTo("2500.12345678");
        assertThat(read.snapshot().input()).isEqualTo(value.input());
        assertThat(read.snapshot().input().matches(read.snapshot().latestGoldDate(),
                read.snapshot().gold(), read.createdAt())).isTrue();
    }

    private GoldResearchSnapshot precisionInput(String price, OffsetDateTime collectedAt,
            OffsetDateTime createdAt) {
        GoldResearchSnapshot source = snapshot("2026-08-24", price);
        GoldReturnMetrics gold = new GoldReturnMetrics(source.gold().currentPrice(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, collectedAt);
        source = new GoldResearchSnapshot(source.analysisDate(), source.latestGoldDate(),
                source.latestRealRateDate(), source.latestDollarIndexDate(), gold, source.realRate(),
                source.dollarIndex(), source.realRateAssessment(), source.dollarIndexAssessment(),
                RESEARCH_VERSION, source.disclaimer());
        return GoldSnapshotFixtures.withInput(source,
                LocalDate.parse("2026-08-24"), RESEARCH_VERSION, createdAt);
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
        save("2099-08-22");
        save("2099-08-25");
        save("2099-08-24");

        assertThat(repository.findRecent(2))
                .extracting(record ->
                        record.snapshot().analysisDate())
                .containsExactly(
                        LocalDate.parse("2099-08-25"),
                        LocalDate.parse("2099-08-24")
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
                        new BigDecimal("18.7500"),
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
