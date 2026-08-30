package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证扩大样本诊断直接使用本地历史数据，不依赖大模型回测结果。 */
class HistoricalHorizonDiagnosticServiceTests {

    @Test
    void diagnosesSelectedHistory() {
        LocalDate date = LocalDate.parse("2026-01-01");
        GoldDailyBarRepository bars = mock(GoldDailyBarRepository.class);
        BacktestDateSelector selector = mock(BacktestDateSelector.class);
        GoldResearchSnapshotService snapshots = mock(GoldResearchSnapshotService.class);
        BacktestService backtests = mock(BacktestService.class);
        when(bars.findAll("XAUUSD", "twelve_data")).thenReturn(futureBars());
        when(selector.selectBars(futureBars(), 1, BacktestSampleSet.HOLDOUT))
                .thenReturn(List.of(date));
        GoldResearchSnapshot snapshot = snapshot();
        when(snapshots.createSnapshot(date)).thenReturn(snapshot);

        HistoricalHorizonReport report = new HistoricalHorizonDiagnosticService(
                bars, selector, snapshots, new GoldForecastRule(),
                new FactorDiagnosticService(backtests)
        ).diagnose(1);

        assertThat(report.requestedSamples()).isEqualTo(1);
        assertThat(report.horizons()).extracting(HorizonDiagnostic::sessions)
                .containsExactly(1, 5, 20);
        assertThat(report.horizons()).extracting(HorizonDiagnostic::sampleCount)
                .containsOnly(1);
        assertThat(report.horizons())
                .allSatisfy(horizon -> assertThat(horizon.volatility())
                        .hasSize(3));
    }

    private GoldResearchSnapshot snapshot() {
        GoldResearchSnapshot snapshot = mock(GoldResearchSnapshot.class);
        GoldReturnMetrics gold = mock(GoldReturnMetrics.class);
        when(snapshot.gold()).thenReturn(gold);
        when(gold.currentPrice()).thenReturn(new BigDecimal("100"));
        when(gold.return1()).thenReturn(BigDecimal.ZERO);
        when(gold.return5()).thenReturn(BigDecimal.ZERO);
        when(gold.return20()).thenReturn(BigDecimal.ONE);
        when(gold.volatility20()).thenReturn(new BigDecimal("18.0000"));
        when(snapshot.realRateAssessment()).thenReturn(new ResearchFactorAssessment(
                GoldFactorStatus.SUPPORTIVE, "test", "test"
        ));
        when(snapshot.dollarIndexAssessment()).thenReturn(new ResearchFactorAssessment(
                GoldFactorStatus.NEUTRAL, "test", "test"
        ));
        return snapshot;
    }

    private List<GoldDailyBar> futureBars() {
        List<GoldDailyBar> result = new ArrayList<>();
        LocalDate date = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 30; index++) {
            BigDecimal close = new BigDecimal("100")
                    .add(BigDecimal.valueOf(index));
            result.add(new GoldDailyBar(
                    "XAUUSD", date.plusDays(index), close, close, close, close,
                    "usd", "troy_ounce", "twelve_data",
                    OffsetDateTime.parse("2026-01-01T00:00:00Z")
            ));
        }
        return result;
    }
}
