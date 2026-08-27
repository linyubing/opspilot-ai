package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证黄金方向预测的幂等保存、JSONB 往返和条件解析。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcGoldForecastRepositoryTests {

    @Autowired private GoldForecastRepository repository;
    @Autowired private GoldResearchSnapshotRepository snapshotRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    private UUID snapshotId;

    @BeforeEach
    void setUp() {
        snapshotId = snapshotRepository.saveIfAbsent(
                GoldForecastTestFixtures.snapshot("4520.00894962").snapshot(),
                OffsetDateTime.parse("2026-08-27T07:30:01Z")
        ).record().id();
        jdbcTemplate.update("delete from gold_direction_forecast where snapshot_id = ?", snapshotId);
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from gold_direction_forecast where snapshot_id = ?", snapshotId);
    }

    @Test
    @DisplayName("首次保存成功而重复幂等键返回原记录且不覆盖")
    void savesOnceWithoutOverwrite() {
        SaveGoldForecastResult first = repository.saveIfAbsent(candidate("原始依据"));
        SaveGoldForecastResult repeated = repository.saveIfAbsent(candidate("覆盖依据"));

        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.record().id()).isEqualTo(first.record().id());
        assertThat(repeated.record().reasoning()).isEqualTo("原始依据");
        assertThat(repeated.record().invalidationConditions())
                .containsExactly("条件一", "条件二");
    }

    @Test
    @DisplayName("待验证记录只能解析一次")
    void resolvesPendingOnlyOnce() {
        StoredGoldDirectionForecast saved = repository.saveIfAbsent(candidate("依据")).record();
        ForecastResolution first = new ForecastResolution(
                LocalDate.parse("2026-08-27"), new BigDecimal("4550.00000000"),
                new BigDecimal("0.663503"), ForecastDirection.BULLISH, true,
                OffsetDateTime.parse("2026-08-28T01:00:00Z")
        );
        StoredGoldDirectionForecast resolved = repository.resolve(saved.id(), first);
        StoredGoldDirectionForecast repeated = repository.resolve(saved.id(), new ForecastResolution(
                LocalDate.parse("2026-08-28"), new BigDecimal("4400.00000000"),
                new BigDecimal("-2.000000"), ForecastDirection.BEARISH, false,
                OffsetDateTime.parse("2026-08-29T01:00:00Z")
        ));

        assertThat(resolved.status()).isEqualTo(ForecastStatus.RESOLVED);
        assertThat(repeated.targetDate()).isEqualTo(first.targetDate());
        assertThat(repository.findPending(10)).isEmpty();
        assertThat(repository.findAllForEvaluation()).extracting(StoredGoldDirectionForecast::id)
                .contains(saved.id());
    }

    private StoredGoldDirectionForecast candidate(String reasoning) {
        return new StoredGoldDirectionForecast(
                UUID.randomUUID(), snapshotId, LocalDate.parse("2026-08-26"),
                new BigDecimal("4520.00894962"), ForecastDirection.NEUTRAL,
                reasoning, List.of("条件一", "条件二"), "glm-4.7",
                "gold-direction-forecast-prompt-v1", "a".repeat(64),
                GoldForecastRule.RULE_VERSION, "原始 JSON", ForecastStatus.PENDING,
                null, null, null, null, null, null,
                OffsetDateTime.parse("2026-08-27T08:00:00Z")
        );
    }
}
