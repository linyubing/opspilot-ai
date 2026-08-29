package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 A/B 回测只比较相同历史样本，并正确计算指标变化。 */
class BacktestComparisonServiceTests {

    private final UUID baselineId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private BacktestService backtests;
    private BacktestEvaluationService evaluation;
    private BacktestComparisonService service;

    @BeforeEach
    void setUp() {
        backtests = mock(BacktestService.class);
        evaluation = mock(BacktestEvaluationService.class);
        service = new BacktestComparisonService(backtests, evaluation);
        when(backtests.get(baselineId)).thenReturn(task(
                baselineId,
                BacktestPromptBuilder.VERSION
        ));
        when(backtests.get(candidateId)).thenReturn(task(
                candidateId,
                CandidateBacktestPromptBuilder.VERSION
        ));
        var dates = List.of(
                LocalDate.parse("2026-08-18"),
                LocalDate.parse("2026-08-19")
        );
        when(backtests.samples(baselineId)).thenReturn(dates);
        when(backtests.samples(candidateId)).thenReturn(dates);
        when(evaluation.evaluate(baselineId)).thenReturn(result("0.4000", "0.4500"));
        when(evaluation.evaluate(candidateId)).thenReturn(result("0.5500", "0.5200"));
    }

    @Test
    void comparesSameSamples() {
        BacktestComparison result = service.compare(baselineId, candidateId);

        assertThat(result.sampleCount()).isEqualTo(2);
        assertThat(result.accuracyChange()).isEqualByComparingTo("0.1500");
        assertThat(result.balancedAccuracyChange()).isEqualByComparingTo("0.0700");
    }

    @Test
    void rejectsDifferentSamples() {
        when(backtests.samples(candidateId)).thenReturn(List.of(
                LocalDate.parse("2026-08-17")
        ));

        assertThatThrownBy(() -> service.compare(baselineId, candidateId))
                .isInstanceOf(InvalidBacktestRequestException.class)
                .hasMessageContaining("样本日期不一致");
    }

    private BacktestTask task(UUID id, String version) {
        var now = OffsetDateTime.parse("2026-08-30T00:00:00Z");
        return new BacktestTask(
                id, LocalDate.parse("2026-08-18"),
                LocalDate.parse("2026-08-19"), 2,
                "glm-4.7", version, "rule-v1",
                BacktestStatus.COMPLETED, 2, 1, 0,
                null, now, now, now
        );
    }

    private BacktestEvaluation result(String accuracy, String balanced) {
        var empty = new DirectionEvaluation(
                ForecastDirection.BULLISH, 0, 0, null
        );
        return new BacktestEvaluation(
                "BACKTEST", 2, new BigDecimal(accuracy), null,
                null, null, null, new BigDecimal(balanced),
                new ConfusionMatrix(
                        new DirectionCounts(0, 0, 0),
                        new DirectionCounts(0, 0, 0),
                        new DirectionCounts(0, 0, 0)
                ),
                empty, empty, empty
        );
    }
}
