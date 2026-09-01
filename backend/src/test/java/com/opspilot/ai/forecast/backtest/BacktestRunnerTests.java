package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GeneratedGoldForecast;
import com.opspilot.ai.forecast.GoldDirectionForecastContent;
import com.opspilot.ai.forecast.GoldForecastGateway;
import com.opspilot.ai.forecast.GoldForecastAiUnavailableException;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证单条历史预测的模型调用、真实结算和幂等保存。 */
class BacktestRunnerTests {

    private static final UUID TASK_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate DATE = LocalDate.parse("2026-08-20");
    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    private BacktestRepository repo;
    private GoldDailyBarRepository barRepo;
    private GoldResearchSnapshotService snapshots;
    private GoldForecastGateway gateway;
    private BacktestRunner runner;

    @BeforeEach
    void setUp() {
        repo = mock(BacktestRepository.class);
        barRepo = mock(GoldDailyBarRepository.class);
        snapshots = mock(GoldResearchSnapshotService.class);
        gateway = mock(GoldForecastGateway.class);
        runner = new BacktestRunner(
                repo, barRepo, snapshots, new BacktestPromptBuilder(),
                new CandidateBacktestPromptBuilder(),
                new ImprovedBacktestPromptBuilder(),
                new CalibratedBacktestPromptBuilder(new BacktestPromptBuilder()),
                gateway, new GoldForecastValidator(),
                new GoldForecastRule(), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(repo.findTask(TASK_ID)).thenReturn(Optional.of(task()));
        when(repo.findSampleDates(TASK_ID)).thenReturn(List.of(DATE));
        when(repo.findDoneDates(TASK_ID)).thenReturn(Set.of());
        when(barRepo.findNext(
                "XAUUSD", "twelve_data", DATE
        )).thenReturn(Optional.of(
                bar(LocalDate.parse("2026-08-24"), "2520")
        ));
        when(snapshots.createSnapshot(DATE)).thenReturn(snapshot());
        when(gateway.generate(any())).thenReturn(new GeneratedGoldForecast(
                "glm-4.7", "固定 JSON",
                new GoldDirectionForecastContent(
                        ForecastDirection.BULLISH,
                        "黄金动量提供支撑",
                        List.of("实际利率明显上升")
                )
        ));
        when(repo.saveCase(any())).thenReturn(true);
    }

    @Test
    void predictsAndSettlesWithNextValidPrice() {
        runner.run(TASK_ID);

        ArgumentCaptor<BacktestCase> captor =
                ArgumentCaptor.forClass(BacktestCase.class);
        verify(repo).saveCase(captor.capture());
        BacktestCase item = captor.getValue();
        assertThat(item.asOfDate()).isEqualTo(DATE);
        assertThat(item.targetDate()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(item.targetPrice()).isEqualByComparingTo("2520");
        assertThat(item.actualReturn()).isEqualByComparingTo("0.800000");
        assertThat(item.actualDirection()).isEqualTo(ForecastDirection.BULLISH);
        assertThat(item.hit()).isTrue();
        assertThat(item.promptHash()).hasSize(64);
        verify(repo).complete(
                TASK_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void usesCandidatePromptVersion() {
        when(repo.findTask(TASK_ID)).thenReturn(Optional.of(task(
                CandidateBacktestPromptBuilder.VERSION
        )));

        runner.run(TASK_ID);

        ArgumentCaptor<com.opspilot.ai.forecast.GoldForecastPrompt> captor =
                ArgumentCaptor.forClass(
                        com.opspilot.ai.forecast.GoldForecastPrompt.class
                );
        verify(gateway).generate(captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(
                CandidateBacktestPromptBuilder.VERSION
        );
        assertThat(captor.getValue().content()).contains("因子发生冲突时");
    }

    @Test
    void usesImprovedPromptVersion() {
        when(repo.findTask(TASK_ID)).thenReturn(Optional.of(task(
                ImprovedBacktestPromptBuilder.VERSION
        )));

        runner.run(TASK_ID);

        ArgumentCaptor<com.opspilot.ai.forecast.GoldForecastPrompt> captor =
                ArgumentCaptor.forClass(
                        com.opspilot.ai.forecast.GoldForecastPrompt.class
                );
        verify(gateway).generate(captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(
                ImprovedBacktestPromptBuilder.VERSION
        );
        assertThat(captor.getValue().content()).contains("中性只能用于");
    }

    @Test
    void usesCalibratedPromptVersion() {
        when(repo.findTask(TASK_ID)).thenReturn(Optional.of(task(
                BacktestPromptVersion.CALIBRATED.version()
        )));

        runner.run(TASK_ID);

        ArgumentCaptor<com.opspilot.ai.forecast.GoldForecastPrompt> captor =
                ArgumentCaptor.forClass(
                        com.opspilot.ai.forecast.GoldForecastPrompt.class
                );
        verify(gateway).generate(captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(
                "gold-backtest-prompt-v4"
        );
        assertThat(captor.getValue().content())
                .contains("0.5%")
                .contains("-0.5%")
                .contains("NEUTRAL");
    }

    @Test
    void skipsCompletedDateWithoutCallingModel() {
        when(repo.findDoneDates(TASK_ID)).thenReturn(Set.of(DATE));

        runner.run(TASK_ID);

        verify(gateway, never()).generate(any());
        verify(repo, never()).saveCase(any());
        verify(repo).complete(
                TASK_ID,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void failsWhenFrozenSamplePlanIsIncomplete() {
        when(repo.findSampleDates(TASK_ID)).thenReturn(List.of());

        runner.run(TASK_ID);

        verify(repo).fail(eq(TASK_ID), contains("冻结样本计划不完整"));
        verify(gateway, never()).generate(any());
        verify(repo, never()).complete(any(), any());
    }

    @Test
    void failsTaskWhenModelCallTimesOut() {
        when(gateway.generate(any())).thenThrow(
                new GoldForecastAiUnavailableException(
                        "黄金方向预测模型暂时不可用，请稍后重试",
                        new java.net.SocketTimeoutException("Read timed out")
                )
        );

        runner.run(TASK_ID);

        verify(repo).fail(
                eq(TASK_ID),
                contains("黄金方向预测模型暂时不可用")
        );
        verify(repo, never()).recordFailure(any(), any());
        verify(repo, never()).complete(any(), any());
    }

    @Test
    void rejectsSnapshotAfterCaseDate() {
        LocalDate pastDate = DATE.minusDays(1);
        when(snapshots.createSnapshot(DATE)).thenReturn(
                snapshotFromDate(DATE.plusDays(1))
        );

        runner.run(TASK_ID);

        verify(repo).recordFailure(eq(TASK_ID), contains("分析日期必须等于回测日期"));
        verify(repo, never()).saveCase(any());
    }

    @Test
    void rejectsFutureRealRateObservation() {
        when(snapshots.createSnapshot(DATE)).thenReturn(
                snapshotWithRealRateDate(DATE.plusDays(1))
        );

        runner.run(TASK_ID);

        verify(repo).recordFailure(eq(TASK_ID), contains("实际利率数据日期不得晚于回测日期"));
        verify(repo, never()).saveCase(any());
    }

    @Test
    void rejectsFutureDollarIndexObservation() {
        when(snapshots.createSnapshot(DATE)).thenReturn(
                snapshotWithDollarIndexDate(DATE.plusDays(1))
        );

        runner.run(TASK_ID);

        verify(repo).recordFailure(eq(TASK_ID), contains("美元指数数据日期不得晚于回测日期"));
        verify(repo, never()).saveCase(any());
    }

    @Test
    void rejectsSettlementOnOrBeforeCaseDate() {
        when(barRepo.findNext(
                "XAUUSD", "twelve_data", DATE
        )).thenReturn(Optional.of(bar(DATE, "2520")));

        runner.run(TASK_ID);

        verify(repo).recordFailure(eq(TASK_ID), contains("结算日必须晚于回测日期"));
        verify(repo, never()).saveCase(any());
    }

    @Test
    void usesBaseDayCloseForOhlcBacktest() {
        runner.run(TASK_ID);

        ArgumentCaptor<BacktestCase> captor =
                ArgumentCaptor.forClass(BacktestCase.class);
        verify(repo).saveCase(captor.capture());
        BacktestCase item = captor.getValue();
        assertThat(item.basePrice()).isEqualByComparingTo("2500");
    }

    @Test
    void usesNextAvailableBarCloseForSettlement() {
        runner.run(TASK_ID);

        ArgumentCaptor<BacktestCase> captor =
                ArgumentCaptor.forClass(BacktestCase.class);
        verify(repo).saveCase(captor.capture());
        BacktestCase item = captor.getValue();
        assertThat(item.targetPrice()).isEqualByComparingTo("2520");
    }

    @Test
    void calculatesCloseToCloseReturn() {
        runner.run(TASK_ID);

        ArgumentCaptor<BacktestCase> captor =
                ArgumentCaptor.forClass(BacktestCase.class);
        verify(repo).saveCase(captor.capture());
        BacktestCase item = captor.getValue();
        // (2520 - 2500) / 2500 * 100 = 0.8
        assertThat(item.actualReturn()).isEqualByComparingTo("0.800000");
    }

    @Test
    void doesNotUseCurrentSystemDateForHistoricalCase() {
        runner.run(TASK_ID);

        ArgumentCaptor<BacktestCase> captor =
                ArgumentCaptor.forClass(BacktestCase.class);
        verify(repo).saveCase(captor.capture());
        BacktestCase item = captor.getValue();
        assertThat(item.asOfDate()).isEqualTo(DATE);
    }

    private BacktestTask task() {
        return task(BacktestPromptBuilder.VERSION);
    }

    private BacktestTask task(String promptVersion) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new BacktestTask(
                TASK_ID, DATE, DATE, 1, "glm-4.7",
                promptVersion, GoldForecastRule.RULE_VERSION,
                BacktestStatus.RUNNING, 0, 0, 0,
                null, now, now, null
        );
    }

    private GoldDailyBar bar(LocalDate date, String close) {
        BigDecimal value = new BigDecimal(close);
        return new GoldDailyBar(
                "XAUUSD", date, value,
                value.add(BigDecimal.TEN),
                value.subtract(BigDecimal.TEN), value,
                "usd", "troy_ounce", "twelve_data",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    private GoldResearchSnapshot snapshot() {
        return snapshotFromDate(DATE);
    }

    private GoldResearchSnapshot snapshotFromDate(LocalDate analysisDate) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new GoldResearchSnapshot(
                analysisDate, analysisDate, analysisDate, analysisDate,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE, "rate-v1", "支撑"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "dollar-v1", "中性"
                ),
                "gold-multifactor-v2", "不构成投资建议"
        );
    }

    private GoldResearchSnapshot snapshotWithRealRateDate(LocalDate realRateDate) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new GoldResearchSnapshot(
                DATE, DATE, realRateDate, DATE,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE, "rate-v1", "支撑"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "dollar-v1", "中性"
                ),
                "gold-multifactor-v2", "不构成投资建议"
        );
    }

    private GoldResearchSnapshot snapshotWithDollarIndexDate(LocalDate dollarIndexDate) {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new GoldResearchSnapshot(
                DATE, DATE, DATE, dollarIndexDate,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("1.8"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, now
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE, "rate-v1", "支撑"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "dollar-v1", "中性"
                ),
                "gold-multifactor-v2", "不构成投资建议"
        );
    }
}
