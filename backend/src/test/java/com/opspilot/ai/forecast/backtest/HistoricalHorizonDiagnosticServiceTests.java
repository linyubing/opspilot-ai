package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.RealRateFactorEvaluator;
import com.opspilot.ai.analysis.DollarIndexFactorEvaluator;
import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;

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
        when(snapshots.createSnapshot(eq(date), anyList(), any(OffsetDateTime.class))).thenReturn(snapshot);

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

    @ParameterizedTest
    @CsvSource({"1,0", "2,1"})
    @org.junit.jupiter.api.DisplayName("历史评分保留原始跨度，第一或中间目标未确认不计分")
    void preservesUnconfirmedTarget(int index, int nextCount) {
        LocalDate date = LocalDate.parse("2026-01-01");
        GoldDailyBarRepository bars = mock(GoldDailyBarRepository.class);
        BacktestDateSelector selector = mock(BacktestDateSelector.class);
        GoldResearchSnapshotService snapshots = mock(GoldResearchSnapshotService.class);
        BacktestService backtests = mock(BacktestService.class);
        List<GoldDailyBar> history = futureBars();
        GoldDailyBar bar = history.get(index);
        history.set(index, new GoldDailyBar(bar.symbol(), bar.priceDate(), bar.open(), bar.high(),
                bar.low(), bar.close(), bar.currency(), bar.unit(), bar.provider(), bar.collectedAt()));
        when(bars.findAll("XAUUSD", "twelve_data")).thenReturn(history);
        when(selector.selectBars(history, 1, BacktestSampleSet.HOLDOUT)).thenReturn(List.of(date));
        GoldResearchSnapshot snapshot = snapshot();
        when(snapshots.createSnapshot(eq(date), anyList(), any(OffsetDateTime.class))).thenReturn(snapshot);

        HistoricalHorizonReport result = new HistoricalHorizonDiagnosticService(bars, selector,
                snapshots, new GoldForecastRule(), new FactorDiagnosticService(backtests)).diagnose(1);

        assertThat(result.horizons()).extracting(HorizonDiagnostic::sampleCount)
                .containsExactly(nextCount, 0, 0);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("历史诊断共用首次黄金价格和核验时间，不让覆盖价格改变评分")
    void freezesHistoricalPrices() {
        LocalDate date = LocalDate.parse("2026-01-21");
        GoldDailyBarRepository bars = mock(GoldDailyBarRepository.class);
        MacroObservationRepository macro = mock(MacroObservationRepository.class);
        BacktestDateSelector selector = mock(BacktestDateSelector.class);
        BacktestService backtests = mock(BacktestService.class);
        List<GoldDailyBar> initial = new ArrayList<>();
        List<GoldDailyBar> overwritten = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            LocalDate day = LocalDate.parse("2026-01-01").plusDays(i);
            initial.add(mathBar(day, new BigDecimal("100").add(new BigDecimal("0.1").multiply(BigDecimal.valueOf(i)))));
            if (i <= 20) {
                overwritten.add(mathBar(day, new BigDecimal("98").add(new BigDecimal("0.1").multiply(BigDecimal.valueOf(i)))));
            }
        }
        when(bars.findAll("XAUUSD", "twelve_data")).thenReturn(initial);
        when(bars.findRecent(eq("XAUUSD"), eq("twelve_data"), eq(date), anyInt())).thenReturn(overwritten);
        when(selector.selectBars(initial, 1, BacktestSampleSet.HOLDOUT)).thenReturn(List.of(date));
        List<MacroObservation> rates = new ArrayList<>();
        List<MacroObservation> dollars = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            rates.add(new MacroObservation(UUID.randomUUID(), "DFII10", date.minusDays(i),
                    new BigDecimal("2"), "percent", "fred", OffsetDateTime.parse("2026-02-01T00:00:00Z"), null));
            dollars.add(new MacroObservation(UUID.randomUUID(), "DTWEXBGS", date.minusDays(i),
                    new BigDecimal("120"), "index", "fred", OffsetDateTime.parse("2026-02-01T00:00:00Z"), null));
        }
        when(macro.findRecent("DFII10", date, 120)).thenReturn(rates);
        when(macro.findRecent("DTWEXBGS", date, 120)).thenReturn(dollars);
        GoldResearchSnapshotService snapshots = new GoldResearchSnapshotService(bars, macro,
                new RealRateFactorEvaluator(), new DollarIndexFactorEvaluator());

        HistoricalHorizonReport result = new HistoricalHorizonDiagnosticService(bars, selector,
                snapshots, new GoldForecastRule(), new FactorDiagnosticService(backtests)).diagnose(1);

        // 原窗口基准102，次日102.1，中性；20日动量上涨，所以这一因子应未命中。
        assertThat(result.horizons().getFirst().factors().getFirst().accuracy()).isEqualByComparingTo("0");
        verify(bars, never()).findRecent(anyString(), anyString(), any(LocalDate.class), anyInt());
    }

    private GoldDailyBar mathBar(LocalDate date, BigDecimal close) {
        OffsetDateTime checked = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        return new GoldDailyBar("XAUUSD", date, close, close, close, close, "usd", "troy_ounce",
                "twelve_data", checked,
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, date, checked, "a".repeat(64)));
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
                    OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                    // 数学样例，真实实验不能引用这些确认。
                    new GoldBarConfirmation(GoldBarConfirmation.SOURCE, date.plusDays(index),
                            OffsetDateTime.parse("2026-02-01T00:00:00Z"), "a".repeat(64))
            ));
        }
        return result;
    }
}
