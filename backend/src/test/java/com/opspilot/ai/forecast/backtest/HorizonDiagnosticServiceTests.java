package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.GoldFactorStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证周期诊断按第 1、5、20 个真实交易日重新结算因子表现。 */
class HorizonDiagnosticServiceTests {

    @Test
    void comparesThreeHorizons() {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        GoldDailyBarRepository bars = mock(GoldDailyBarRepository.class);
        BacktestCase item = item();
        LocalDate date = item.asOfDate();
        when(backtests.results(id, 120)).thenReturn(List.of(item));
        when(bars.findAfter(
                eq("XAUUSD"), eq("twelve_data"), eq(date), anyInt()
        ))
                .thenReturn(futureBars());
        FactorDiagnosticService factors = new FactorDiagnosticService(backtests);

        HorizonDiagnosticReport report = new HorizonDiagnosticService(
                backtests, bars, new GoldForecastRule(), factors
        ).diagnose(id);

        assertThat(report.horizons()).extracting(HorizonDiagnostic::sessions)
                .containsExactly(1, 5, 20);
        assertThat(report.horizons()).extracting(HorizonDiagnostic::sampleCount)
                .containsOnly(1);
        assertThat(momentumAccuracy(report, 1)).isEqualByComparingTo("0.0000");
        assertThat(momentumAccuracy(report, 5)).isEqualByComparingTo("1.0000");
        assertThat(momentumAccuracy(report, 20)).isEqualByComparingTo("0.0000");
        verify(bars, times(1)).findAfter(
                eq("XAUUSD"), eq("twelve_data"), eq(date), anyInt()
        );
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,1"})
    @org.junit.jupiter.api.DisplayName("中间目标未确认只排除受影响周期，不删除行缩短周期")
    void preservesUnconfirmedTarget(int index, int nextCount) {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        GoldDailyBarRepository bars = mock(GoldDailyBarRepository.class);
        BacktestCase item = item();
        List<GoldDailyBar> future = futureBars();
        GoldDailyBar bar = future.get(index);
        future.set(index, new GoldDailyBar(bar.symbol(), bar.priceDate(), bar.open(), bar.high(),
                bar.low(), bar.close(), bar.currency(), bar.unit(), bar.provider(), bar.collectedAt()));
        when(backtests.results(id, 120)).thenReturn(List.of(item));
        LocalDate baseDate = item.asOfDate();
        when(bars.findAfter(eq("XAUUSD"), eq("twelve_data"), eq(baseDate), anyInt()))
                .thenReturn(future);

        HorizonDiagnosticReport result = new HorizonDiagnosticService(backtests, bars,
                new GoldForecastRule(), new FactorDiagnosticService(backtests)).diagnose(id);

        assertThat(result.horizons()).extracting(HorizonDiagnostic::sampleCount)
                .containsExactly(nextCount, 0, 0);
    }

    private BigDecimal momentumAccuracy(
            HorizonDiagnosticReport report,
            int sessions
    ) {
        return report.horizons().stream()
                .filter(item -> item.sessions() == sessions)
                .findFirst().orElseThrow()
                .factors().getFirst().accuracy();
    }

    private BacktestCase item() {
        BacktestCase item = mock(BacktestCase.class);
        GoldResearchSnapshot snapshot = mock(GoldResearchSnapshot.class);
        GoldReturnMetrics gold = mock(GoldReturnMetrics.class);
        when(item.asOfDate()).thenReturn(LocalDate.parse("2026-01-01"));
        when(item.basePrice()).thenReturn(new BigDecimal("100"));
        when(item.snapshot()).thenReturn(snapshot);
        when(snapshot.gold()).thenReturn(gold);
        when(gold.return1()).thenReturn(BigDecimal.ZERO);
        when(gold.return5()).thenReturn(BigDecimal.ZERO);
        when(gold.return20()).thenReturn(BigDecimal.ONE);
        when(snapshot.realRateAssessment()).thenReturn(new ResearchFactorAssessment(
                GoldFactorStatus.NEUTRAL, "test", "test"
        ));
        when(snapshot.dollarIndexAssessment()).thenReturn(new ResearchFactorAssessment(
                GoldFactorStatus.NEUTRAL, "test", "test"
        ));
        return item;
    }

    private List<GoldDailyBar> futureBars() {
        List<GoldDailyBar> result = new ArrayList<>();
        LocalDate date = LocalDate.parse("2026-01-02");
        int session = 0;
        while (session < 20) {
            if (date.getDayOfWeek().getValue() <= 5) {
                session++;
                BigDecimal value = session == 1
                        ? new BigDecimal("100.2")
                        : session == 5
                        ? new BigDecimal("101")
                        : session == 20
                        ? new BigDecimal("90")
                        : new BigDecimal("100");
                result.add(new GoldDailyBar(
                        "XAUUSD", date, value, value, value, value,
                        "usd", "troy_ounce", "twelve_data",
                        OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                        // 数学样例的显式确认，不作为市场准确率依据。
                        new GoldBarConfirmation(GoldBarConfirmation.SOURCE, date,
                                OffsetDateTime.parse("2026-02-01T00:00:00Z"), "a".repeat(64))
                ));
            }
            date = date.plusDays(1);
        }
        return result;
    }
}
