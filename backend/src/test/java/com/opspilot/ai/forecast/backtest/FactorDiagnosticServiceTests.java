package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证因子诊断分别计算覆盖率、总体命中和明确方向命中。 */
class FactorDiagnosticServiceTests {

    @Test
    void diagnosesEachFactorIndependently() {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        List<BacktestCase> cases = List.of(
                item("1", ForecastDirection.BULLISH,
                        GoldFactorStatus.SUPPORTIVE,
                        GoldFactorStatus.PRESSURING),
                item("0", ForecastDirection.NEUTRAL,
                        GoldFactorStatus.NEUTRAL,
                        GoldFactorStatus.NEUTRAL),
                item("-1", ForecastDirection.BULLISH,
                        GoldFactorStatus.PRESSURING,
                        GoldFactorStatus.SUPPORTIVE)
        );
        when(backtests.results(id, 120)).thenReturn(cases);

        FactorDiagnosticReport report =
                new FactorDiagnosticService(backtests).diagnose(id);

        FactorDiagnostic momentum = report.factors().getFirst();
        assertThat(momentum.factor()).isEqualTo("GOLD_MOMENTUM_20");
        assertThat(momentum.coverage()).isEqualByComparingTo("0.6667");
        assertThat(momentum.accuracy()).isEqualByComparingTo("0.6667");
        assertThat(momentum.directionalAccuracy())
                .isEqualByComparingTo("0.5000");
        assertThat(momentum.signals()).isEqualTo(new DirectionCounts(1, 1, 1));

        FactorDiagnostic dollar = report.factors().get(2);
        assertThat(dollar.directionalHitCount()).isEqualTo(1);
        assertThat(dollar.directionalAccuracy()).isEqualByComparingTo("0.5000");
    }

    @Test
    void diagnosesShortTermReversal() {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        List<BacktestCase> cases = List.of(
                item("1", "2", "0", ForecastDirection.BEARISH),
                item("-1", "-2", "0", ForecastDirection.BULLISH),
                item("1", "-2", "0", ForecastDirection.NEUTRAL)
        );
        when(backtests.results(id, 120)).thenReturn(cases);

        FactorDiagnosticReport report =
                new FactorDiagnosticService(backtests).diagnose(id);

        FactorDiagnostic reversal = report.factors().stream()
                .filter(item -> "SHORT_TERM_REVERSAL".equals(item.factor()))
                .findFirst()
                .orElseThrow();
        assertThat(reversal.accuracy()).isEqualByComparingTo("1.0000");
        assertThat(reversal.signals()).isEqualTo(new DirectionCounts(1, 1, 1));
    }

    private BacktestCase item(
            String return20,
            ForecastDirection actual,
            GoldFactorStatus realRate,
            GoldFactorStatus dollar
    ) {
        BacktestCase item = mock(BacktestCase.class);
        GoldResearchSnapshot snapshot = mock(GoldResearchSnapshot.class);
        when(item.snapshot()).thenReturn(snapshot);
        when(item.actualDirection()).thenReturn(actual);
        when(snapshot.gold()).thenReturn(mock(GoldReturnMetrics.class));
        when(snapshot.gold().return1()).thenReturn(BigDecimal.ZERO);
        when(snapshot.gold().return5()).thenReturn(BigDecimal.ZERO);
        when(snapshot.gold().return20()).thenReturn(new BigDecimal(return20));
        when(snapshot.realRateAssessment()).thenReturn(
                new ResearchFactorAssessment(realRate, "test", "test")
        );
        when(snapshot.dollarIndexAssessment()).thenReturn(
                new ResearchFactorAssessment(dollar, "test", "test")
        );
        return item;
    }

    private BacktestCase item(
            String return1,
            String return5,
            String return20,
            ForecastDirection actual
    ) {
        BacktestCase item = mock(BacktestCase.class);
        GoldResearchSnapshot snapshot = mock(GoldResearchSnapshot.class);
        GoldReturnMetrics gold = mock(GoldReturnMetrics.class);
        when(item.snapshot()).thenReturn(snapshot);
        when(item.actualDirection()).thenReturn(actual);
        when(snapshot.gold()).thenReturn(gold);
        when(gold.return1()).thenReturn(new BigDecimal(return1));
        when(gold.return5()).thenReturn(new BigDecimal(return5));
        when(gold.return20()).thenReturn(new BigDecimal(return20));
        when(snapshot.realRateAssessment()).thenReturn(
                new ResearchFactorAssessment(GoldFactorStatus.NEUTRAL, "test", "test")
        );
        when(snapshot.dollarIndexAssessment()).thenReturn(
                new ResearchFactorAssessment(GoldFactorStatus.NEUTRAL, "test", "test")
        );
        return item;
    }
}
