package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
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
        MarketPriceRepository prices = mock(MarketPriceRepository.class);
        BacktestDateSelector selector = mock(BacktestDateSelector.class);
        GoldResearchSnapshotService snapshots = mock(GoldResearchSnapshotService.class);
        BacktestService backtests = mock(BacktestService.class);
        when(prices.findAll("XAUUSD")).thenReturn(futurePrices());
        when(selector.select(futurePrices(), 1, BacktestSampleSet.HOLDOUT))
                .thenReturn(List.of(date));
        GoldResearchSnapshot snapshot = snapshot();
        when(snapshots.createSnapshot(date)).thenReturn(snapshot);

        HistoricalHorizonReport report = new HistoricalHorizonDiagnosticService(
                prices, selector, snapshots, new GoldForecastRule(),
                new FactorDiagnosticService(backtests)
        ).diagnose(1);

        assertThat(report.requestedSamples()).isEqualTo(1);
        assertThat(report.horizons()).extracting(HorizonDiagnostic::sessions)
                .containsExactly(1, 5, 20);
        assertThat(report.horizons()).extracting(HorizonDiagnostic::sampleCount)
                .containsOnly(1);
    }

    private GoldResearchSnapshot snapshot() {
        GoldResearchSnapshot snapshot = mock(GoldResearchSnapshot.class);
        GoldReturnMetrics gold = mock(GoldReturnMetrics.class);
        when(snapshot.gold()).thenReturn(gold);
        when(gold.currentPrice()).thenReturn(new BigDecimal("100"));
        when(gold.return20()).thenReturn(BigDecimal.ONE);
        when(snapshot.realRateAssessment()).thenReturn(new ResearchFactorAssessment(
                GoldFactorStatus.SUPPORTIVE, "test", "test"
        ));
        when(snapshot.dollarIndexAssessment()).thenReturn(new ResearchFactorAssessment(
                GoldFactorStatus.NEUTRAL, "test", "test"
        ));
        return snapshot;
    }

    private List<MarketPrice> futurePrices() {
        List<MarketPrice> result = new ArrayList<>();
        LocalDate date = LocalDate.parse("2026-01-01");
        for (int index = 0; index < 30; index++) {
            result.add(new MarketPrice(
                    "XAUUSD", date.plusDays(index),
                    new BigDecimal("100").add(BigDecimal.valueOf(index)),
                    "usd", "troy_ounce", "test",
                    OffsetDateTime.parse("2026-01-01T00:00:00Z")
            ));
        }
        return result;
    }
}
