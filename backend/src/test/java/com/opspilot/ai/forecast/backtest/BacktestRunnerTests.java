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
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.forecast.NextValidMarketPriceSelector;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
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
    private MarketPriceRepository priceRepo;
    private GoldResearchSnapshotService snapshots;
    private GoldForecastGateway gateway;
    private BacktestRunner runner;

    @BeforeEach
    void setUp() {
        repo = mock(BacktestRepository.class);
        priceRepo = mock(MarketPriceRepository.class);
        snapshots = mock(GoldResearchSnapshotService.class);
        gateway = mock(GoldForecastGateway.class);
        runner = new BacktestRunner(
                repo, priceRepo, snapshots, new BacktestPromptBuilder(), gateway,
                new GoldForecastValidator(), new NextValidMarketPriceSelector(),
                new GoldForecastRule(), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(repo.findTask(TASK_ID)).thenReturn(Optional.of(task()));
        when(repo.findDoneDates(TASK_ID)).thenReturn(Set.of());
        when(priceRepo.findRecent("XAUUSD", DATE, 1))
                .thenReturn(List.of(price(DATE, "2500")));
        when(priceRepo.findAfter("XAUUSD", DATE, 100))
                .thenReturn(List.of(
                        price(LocalDate.parse("2026-08-22"), "2510"),
                        price(LocalDate.parse("2026-08-24"), "2520")
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

    private BacktestTask task() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new BacktestTask(
                TASK_ID, DATE, DATE, 1, "glm-4.7",
                BacktestPromptBuilder.VERSION, GoldForecastRule.RULE_VERSION,
                BacktestStatus.RUNNING, 0, 0, 0,
                null, now, now, null
        );
    }

    private MarketPrice price(LocalDate date, String value) {
        return new MarketPrice(
                "XAUUSD", date, new BigDecimal(value),
                "usd", "troy_ounce", "test",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    private GoldResearchSnapshot snapshot() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new GoldResearchSnapshot(
                DATE, DATE, DATE, DATE,
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
