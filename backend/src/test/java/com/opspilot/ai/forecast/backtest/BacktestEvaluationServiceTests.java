package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证历史回测评估与实时预测评估相互隔离。 */
class BacktestEvaluationServiceTests {

    @Test
    void evaluatesOverallRollingBaselineAndDirections() {
        UUID id = UUID.randomUUID();
        BacktestService service = mock(BacktestService.class);
        BacktestRepository repo = mock(BacktestRepository.class);
        when(repo.findCases(id, 120)).thenReturn(cases(id));
        BacktestEvaluationService evaluation =
                new BacktestEvaluationService(service, repo);

        BacktestEvaluation result = evaluation.evaluate(id);

        assertThat(result.source()).isEqualTo("BACKTEST");
        assertThat(result.sampleCount()).isEqualTo(21);
        assertThat(result.accuracy()).isEqualByComparingTo("0.5238");
        assertThat(result.rolling20Accuracy()).isEqualByComparingTo("0.5000");
        assertThat(result.neutralBaselineAccuracy())
                .isEqualByComparingTo("0.2857");
        assertThat(result.majorityBaselineAccuracy())
                .isEqualByComparingTo("0.3810");
        assertThat(result.accuracyLift()).isEqualByComparingTo("0.1428");
        assertThat(result.balancedAccuracy()).isEqualByComparingTo("0.5238");
        assertThat(result.confusionMatrix()).isEqualTo(new ConfusionMatrix(
                new DirectionCounts(4, 0, 3),
                new DirectionCounts(3, 3, 0),
                new DirectionCounts(0, 4, 4)
        ));
        assertThat(result.bullish().sampleCount()).isEqualTo(7);
        assertThat(result.bullish().accuracy()).isEqualByComparingTo("0.5714");
        assertThat(result.neutral().accuracy()).isEqualByComparingTo("0.4286");
        assertThat(result.bearish().accuracy()).isEqualByComparingTo("0.5714");
    }

    private List<BacktestCase> cases(UUID id) {
        List<BacktestCase> result = new ArrayList<>();
        ForecastDirection[] values = ForecastDirection.values();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-01T00:00:00Z");
        for (int index = 0; index < 21; index++) {
            ForecastDirection predicted = values[index % values.length];
            boolean hit = index % 2 == 0;
            ForecastDirection actual = hit
                    ? predicted
                    : values[(index + 1) % values.length];
            result.add(new BacktestCase(
                    UUID.randomUUID(), id, start.toLocalDate().plusDays(index),
                    null, null, predicted, "依据", List.of("条件"),
                    start.toLocalDate().plusDays(index + 1), null, null,
                    actual, hit, "glm-4.7", "prompt-v1",
                    "a".repeat(64), "rule-v1", "原始响应",
                    start.plusDays(index)
            ));
        }
        return result;
    }
}
