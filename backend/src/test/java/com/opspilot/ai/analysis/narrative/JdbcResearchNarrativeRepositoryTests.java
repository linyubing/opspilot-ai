package com.opspilot.ai.analysis.narrative;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcResearchNarrativeRepositoryTests {

    private static final String RESEARCH_VERSION = "narrative-repository-test-v1";
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(
            2026, 8, 27, 8, 0, 0, 0, ZoneOffset.UTC
    );

    @Autowired
    private ResearchNarrativeRepository repository;

    @Autowired
    private GoldResearchSnapshotRepository snapshotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID snapshotId;

    @BeforeEach
    void setUp() {
        clean();
        snapshotId = snapshotRepository.saveIfAbsent(
                snapshot(),
                CREATED_AT.minusMinutes(1)
        ).record().id();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void savesAndReadsJsonLists() {
        SaveResearchNarrativeResult result = repository.saveIfAbsent(
                candidate("glm-4.7", "prompt-v1", CREATED_AT)
        );

        assertThat(result.created()).isTrue();
        assertThat(result.record().content().risks())
                .containsExactly("日期不一致", "样本有限");
        assertThat(result.record().content().watchList())
                .containsExactly("实际利率", "美元指数");
        assertThat(repository.findByKey(snapshotId, "glm-4.7", "prompt-v1"))
                .contains(result.record());
    }

    @Test
    void repeatedSaveKeepsFirstRecord() {
        StoredResearchNarrative first = candidate(
                "glm-4.7", "prompt-v1", CREATED_AT
        );
        repository.saveIfAbsent(first);

        StoredResearchNarrative changed = new StoredResearchNarrative(
                UUID.randomUUID(), snapshotId,
                new ResearchNarrativeContent(
                        "不应覆盖", "不应覆盖", "不应覆盖",
                        List.of("不应覆盖"), List.of("不应覆盖"),
                        "不构成价格预测、交易或投资建议"
                ),
                "glm-4.7", "prompt-v1", "b".repeat(64),
                "changed", CREATED_AT.plusHours(1)
        );

        SaveResearchNarrativeResult result = repository.saveIfAbsent(changed);

        assertThat(result.created()).isFalse();
        assertThat(result.record().id()).isEqualTo(first.id());
        assertThat(result.record().content().summary()).isEqualTo("双因子研究摘要");
        assertThat(result.record().rawResponse()).isEqualTo("raw-json");
    }

    @Test
    void findsHistoryNewestFirst() {
        repository.saveIfAbsent(candidate("glm-4.7", "prompt-v1", CREATED_AT));
        repository.saveIfAbsent(candidate(
                "glm-4.7", "prompt-v2", CREATED_AT.plusHours(1)
        ));

        assertThat(repository.findBySnapshotId(snapshotId))
                .extracting(StoredResearchNarrative::promptVersion)
                .containsExactly("prompt-v2", "prompt-v1");
    }

    @Test
    void returnsEmptyForMissingIdempotencyKey() {
        assertThat(repository.findByKey(snapshotId, "glm-4.7", "missing"))
                .isEmpty();
    }

    private StoredResearchNarrative candidate(
            String modelName,
            String promptVersion,
            OffsetDateTime createdAt
    ) {
        return new StoredResearchNarrative(
                UUID.randomUUID(), snapshotId,
                new ResearchNarrativeContent(
                        "双因子研究摘要",
                        "实际利率当前为中性。",
                        "美元指数当前提供支持。",
                        List.of("日期不一致", "样本有限"),
                        List.of("实际利率", "美元指数"),
                        "不构成价格预测、交易或投资建议"
                ),
                modelName, promptVersion, "a".repeat(64),
                "raw-json", createdAt
        );
    }

    /** 固定数值仅用于创建外键所需的测试快照，不代表真实行情。 */
    private GoldResearchSnapshot snapshot() {
        LocalDate date = LocalDate.parse("2026-08-27");
        return new GoldResearchSnapshot(
                date, date, date, date,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, CREATED_AT
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.4"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, CREATED_AT
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, CREATED_AT
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "real-rate-v1", "中性"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "dollar-v1", "中性"
                ),
                RESEARCH_VERSION,
                "不构成投资建议"
        );
    }

    private void clean() {
        jdbcTemplate.update("delete from gold_research_narrative");
        jdbcTemplate.update(
                "delete from gold_research_snapshot where research_version = ?",
                RESEARCH_VERSION
        );
    }
}
