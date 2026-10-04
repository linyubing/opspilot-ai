package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金方向预测的幂等保存、JSONB 往返和条件解析。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcGoldForecastRepositoryTests {

    @Autowired private GoldForecastRepository repository;
    @Autowired private GoldResearchSnapshotRepository snapshotRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    private UUID snapshotId;

    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("最老100条永久错日记录不阻塞第101条有效预测，且不移动原承诺")
    void advancesPastMismatch() {
        // 数学样例仅在回滚事务内运行，不写入真实预测统计。
        var wrong = new GoldForecastTiming(LocalDate.parse("2026-08-28"),
                java.time.Instant.parse("2026-08-27T21:00:00Z"),
                java.time.Instant.parse("2026-08-28T21:00:00Z"), "sydney-0700-candidate-v1");
        var correct = new GoldForecastTiming(LocalDate.parse("2026-08-27"),
                java.time.Instant.parse("2026-08-26T21:00:00Z"),
                java.time.Instant.parse("2026-08-27T21:00:00Z"), "sydney-0700-candidate-v1");
        StoredGoldDirectionForecast last = null;
        for (int i = 0; i <= 100; i++) {
            var c = candidate("轮转数学样例", "rotation-" + i,
                    OffsetDateTime.parse("2026-08-27T00:00:00Z").plusSeconds(i));
            last = new StoredGoldDirectionForecast(c.id(), c.snapshotId(), c.baseDate(), c.basePrice(),
                    c.predictedDirection(), c.reasoning(), c.invalidationConditions(), c.modelName(),
                    c.promptVersion(), c.promptHash(), c.forecastRuleVersion(), c.rawResponse(), c.status(),
                    null, null, null, null, null, null, c.createdAt(), i < 100 ? wrong : correct);
            repository.saveIfAbsent(last);
        }
        var bars = org.mockito.Mockito.mock(com.opspilot.ai.marketdata.GoldDailyBarRepository.class);
        var received = OffsetDateTime.parse("2026-08-28T01:00:00Z");
        var bar = new com.opspilot.ai.marketdata.GoldDailyBar("XAUUSD", LocalDate.parse("2026-08-27"),
                new BigDecimal("4520"), new BigDecimal("4550"), new BigDecimal("4500"), new BigDecimal("4520"),
                "usd", "troy_ounce", "twelve_data", received,
                new com.opspilot.ai.marketdata.GoldBarConfirmation(
                        com.opspilot.ai.marketdata.GoldBarConfirmation.SOURCE,
                        LocalDate.parse("2026-08-27"), received, "a".repeat(64)));
        org.mockito.Mockito.when(bars.findNext("XAUUSD", "twelve_data", LocalDate.parse("2026-08-26")))
                .thenReturn(java.util.Optional.of(bar));
        var resolver = new GoldForecastResolutionService(repository, bars, new GoldForecastRule(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-02T12:00:00Z"), java.time.ZoneOffset.UTC));
        assertThat(resolver.resolvePending(100).resolvedCount()).isZero();
        resolver.resolvePending(100);
        assertThat(repository.findByKey(snapshotId, last.modelName(), last.promptVersion(), last.forecastRuleVersion())
                .orElseThrow().status()).isEqualTo(ForecastStatus.RESOLVED);
        assertThat(repository.findAllForEvaluation().stream()
                .filter(f -> f.snapshotId().equals(snapshotId) && !f.promptVersion().equals("rotation-100")))
                .allSatisfy(f -> {
                    assertThat(f.status()).isEqualTo(ForecastStatus.PENDING);
                    assertThat(f.timing().targetDate()).isEqualTo("2026-08-28");
                });
    }

    @Test
    @DisplayName("候选目标及UTC区间往返入库，重复幂等不覆盖原承诺")
    void preservesTiming() {
        var legacy = candidate("发布时间数学样例");
        var timing = new GoldForecastTiming(LocalDate.parse("2026-08-27"),
                java.time.Instant.parse("2026-08-26T21:00:00Z"),
                java.time.Instant.parse("2026-08-27T21:00:00Z"), "sydney-0700-candidate-v1");
        var timed = new StoredGoldDirectionForecast(legacy.id(), legacy.snapshotId(), legacy.baseDate(), legacy.basePrice(),
                legacy.predictedDirection(), legacy.reasoning(), legacy.invalidationConditions(), legacy.modelName(),
                legacy.promptVersion(), legacy.promptHash(), legacy.forecastRuleVersion(), legacy.rawResponse(), legacy.status(),
                legacy.targetDate(), legacy.targetPrice(), legacy.actualReturn(), legacy.actualDirection(), legacy.hit(),
                legacy.resolvedAt(), legacy.createdAt(), timing);
        var saved = repository.saveIfAbsent(timed);
        assertThat(saved.record().timing()).isEqualTo(timing);
        assertThat(repository.saveIfAbsent(legacy).record().timing()).isEqualTo(timing);
        var resolved = repository.resolve(saved.record().id(), new ForecastResolution(LocalDate.parse("2026-08-27"),
                new BigDecimal("4550"), new BigDecimal("0.663503"), ForecastDirection.BULLISH, true,
                OffsetDateTime.parse("2026-08-28T01:00:00Z")));
        assertThat(resolved.timing()).isEqualTo(timing);
    }

    @Test
    @DisplayName("数据库拒绝只有日期而没有完整时段的半记录")
    void rejectsPartialTiming() {
        var saved = repository.saveIfAbsent(candidate("旧记录")).record();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "update gold_direction_forecast set promised_target_date = ? where id = ?",
                LocalDate.parse("2026-08-27"), saved.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findLatestBySnapshotId(snapshotId).orElseThrow().timing()).isNull();
    }

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
    @DisplayName("首次保存成功，而重复幂等键返回原记录且不覆盖")
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
    void resolvesOnce() {
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
        assertThat(repository.findPending(10)).extracting(StoredGoldDirectionForecast::id)
                .doesNotContain(saved.id());
        assertThat(repository.findAllForEvaluation()).extracting(StoredGoldDirectionForecast::id)
                .contains(saved.id());
    }

    @Test
    @DisplayName("按快照查询时返回创建时间最新的预测")
    void findsLatestForecastForSnapshot() {
        repository.saveIfAbsent(candidate(
                "较早预测",
                "prompt-v1",
                OffsetDateTime.parse("2026-08-27T08:00:00Z")
        ));
        StoredGoldDirectionForecast latest = candidate(
                "最新预测",
                "prompt-v2",
                OffsetDateTime.parse("2026-08-27T09:00:00Z")
        );
        repository.saveIfAbsent(latest);

        assertThat(repository.findLatestBySnapshotId(snapshotId))
                .contains(latest);
    }

    private StoredGoldDirectionForecast candidate(String reasoning) {
        return candidate(
                reasoning,
                "gold-direction-forecast-prompt-v1",
                OffsetDateTime.parse("2026-08-27T08:00:00Z")
        );
    }

    private StoredGoldDirectionForecast candidate(
            String reasoning,
            String promptVersion,
            OffsetDateTime createdAt
    ) {
        return new StoredGoldDirectionForecast(
                UUID.randomUUID(), snapshotId, LocalDate.parse("2026-08-26"),
                new BigDecimal("4520.00894962"), ForecastDirection.NEUTRAL,
                reasoning, List.of("条件一", "条件二"), "glm-4.7",
                promptVersion, "a".repeat(64),
                GoldForecastRule.RULE_VERSION, "原始 JSON", ForecastStatus.PENDING,
                null, null, null, null, null, null,
                createdAt
        );
    }
}
