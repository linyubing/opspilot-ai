package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证黄金回测任务、明细、进度和恢复状态的数据库往返。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcBacktestRepositoryTests {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-08-28T08:00:00Z");

    @Autowired
    private BacktestRepository repo;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID taskId;

    @AfterEach
    void clean() {
        if (taskId != null) {
            jdbc.update(
                    "delete from gold_forecast_backtest where id = ?",
                    taskId
            );
        }
    }

    @Test
    void savesTaskAndCaseIdempotently() {
        BacktestTask task = task();
        taskId = task.id();

        List<LocalDate> dates = List.of(
                LocalDate.parse("2026-08-19"),
                LocalDate.parse("2026-08-20")
        );
        BacktestTask created = repo.create(task, dates);
        boolean started = repo.start(taskId, NOW.plusMinutes(1));
        boolean first = repo.saveCase(item(taskId));
        boolean repeated = repo.saveCase(item(taskId));
        BacktestTask running = repo.findTask(taskId).orElseThrow();

        assertThat(created.status()).isEqualTo(BacktestStatus.CREATED);
        assertThat(created.priceBasis()).isEqualTo(BacktestPriceBasis.OHLC_CLOSE);
        assertThat(created.sampleSet()).isEqualTo(BacktestSampleSet.HOLDOUT);
        assertThat(started).isTrue();
        assertThat(first).isTrue();
        assertThat(repeated).isFalse();
        assertThat(running.status()).isEqualTo(BacktestStatus.RUNNING);
        assertThat(running.completedCount()).isEqualTo(1);
        assertThat(running.hitCount()).isEqualTo(1);
        assertThat(repo.findDoneDates(taskId))
                .containsExactly(LocalDate.parse("2026-08-20"));
        assertThat(repo.findSampleDates(taskId)).containsExactlyElementsOf(dates);
        assertThat(repo.findCases(taskId, 10))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.snapshot()).isEqualTo(snapshot());
                    assertThat(saved.predictedDirection())
                            .isEqualTo(ForecastDirection.BULLISH);
                    assertThat(saved.invalidationConditions())
                            .containsExactly("实际利率明显上升");
                });
    }

    @Test
    void startsOnlyOnceAndUpdatesFinalStatus() {
        BacktestTask task = task();
        taskId = task.id();
        repo.create(task, List.of(
                LocalDate.parse("2026-08-19"),
                LocalDate.parse("2026-08-20")
        ));

        assertThat(repo.start(taskId, NOW.plusMinutes(1))).isTrue();
        assertThat(repo.start(taskId, NOW.plusMinutes(2))).isFalse();

        repo.recordFailure(taskId, "单日模型响应不合法");
        BacktestTask withFailure = repo.findTask(taskId).orElseThrow();
        assertThat(withFailure.failedCount()).isEqualTo(1);
        assertThat(withFailure.lastError()).isEqualTo("单日模型响应不合法");

        repo.fail(taskId, "模型暂时不可用");
        assertThat(repo.findTask(taskId).orElseThrow().status())
                .isEqualTo(BacktestStatus.FAILED);
        assertThat(repo.start(taskId, NOW.plusMinutes(3))).isTrue();

        repo.complete(taskId, NOW.plusMinutes(4));
        BacktestTask completed = repo.findTask(taskId).orElseThrow();
        assertThat(completed.status()).isEqualTo(BacktestStatus.COMPLETED);
        assertThat(completed.completedAt()).isEqualTo(NOW.plusMinutes(4));
        assertThat(completed.lastError()).isNull();
    }

    @Test
    void rollsBackTaskWhenSampleDateIsRepeated() {
        BacktestTask task = task();
        taskId = task.id();
        LocalDate date = LocalDate.parse("2026-08-20");

        assertThatThrownBy(() -> repo.create(task, List.of(date, date)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repo.findTask(taskId)).isEmpty();
    }

    private BacktestTask task() {
        return new BacktestTask(
                UUID.randomUUID(),
                LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-08-20"),
                2,
                "glm-4.7",
                "gold-backtest-prompt-v1",
                "gold-direction-rule-v1",
                BacktestPriceBasis.OHLC_CLOSE,
                BacktestSampleSet.HOLDOUT,
                BacktestStatus.CREATED,
                0, 0, 0, null,
                NOW, null, null
        );
    }

    private BacktestCase item(UUID id) {
        return new BacktestCase(
                UUID.randomUUID(), id,
                LocalDate.parse("2026-08-20"), snapshot(),
                new BigDecimal("2500.00000000"),
                ForecastDirection.BULLISH,
                "黄金动量提供支撑",
                List.of("实际利率明显上升"),
                LocalDate.parse("2026-08-21"),
                new BigDecimal("2520.00000000"),
                new BigDecimal("0.800000"),
                ForecastDirection.BULLISH,
                true,
                "glm-4.7",
                "gold-backtest-prompt-v1",
                "a".repeat(64),
                "gold-direction-rule-v1",
                "原始响应",
                NOW.plusMinutes(2)
        );
    }

    /** 固定数值只验证 JSONB 往返，不代表真实市场数据。 */
    private GoldResearchSnapshot snapshot() {
        LocalDate date = LocalDate.parse("2026-08-20");
        return new GoldResearchSnapshot(
                date, date, date, date,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, NOW
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.4"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, NOW
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, NOW
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "real-rate-v1", "中性"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE, "dollar-v1", "支撑"
                ),
                "gold-multifactor-v2",
                "不构成投资建议"
        );
    }
}
