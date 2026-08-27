package com.opspilot.ai.forecast;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldForecastEvaluationServiceTests {

    @Mock
    private GoldForecastRepository forecastRepository;

    private GoldForecastEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new GoldForecastEvaluationService(forecastRepository);
    }

    @Test
    void returnsNullAccuraciesWhenThereAreNoResolvedSamples() {
        when(forecastRepository.findAllForEvaluation()).thenReturn(List.of(
                pending("glm-4.7", "prompt-v1", "rule-v1")
        ));

        GoldForecastEvaluation result = service.evaluate();

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.pendingCount()).isEqualTo(1);
        assertThat(result.resolvedCount()).isZero();
        assertThat(result.overallAccuracy()).isNull();
        assertThat(result.rolling20Accuracy()).isNull();
        assertThat(result.neutralBaselineAccuracy()).isNull();
        assertDirection(result.bullish(), ForecastDirection.BULLISH, 0, 0, null);
        assertDirection(result.neutral(), ForecastDirection.NEUTRAL, 0, 0, null);
        assertDirection(result.bearish(), ForecastDirection.BEARISH, 0, 0, null);
        assertThat(result.versions()).isEmpty();
    }

    @Test
    void calculatesOverallDirectionAndNeutralBaselineAccuracies() {
        when(forecastRepository.findAllForEvaluation()).thenReturn(List.of(
                resolved(ForecastDirection.BULLISH, ForecastDirection.BULLISH,
                        true, "glm-4.7", "prompt-v1", "rule-v1", 5),
                resolved(ForecastDirection.BULLISH, ForecastDirection.BEARISH,
                        false, "glm-4.7", "prompt-v1", "rule-v1", 4),
                resolved(ForecastDirection.NEUTRAL, ForecastDirection.NEUTRAL,
                        true, "glm-4.7", "prompt-v1", "rule-v1", 3),
                pending("glm-4.7", "prompt-v1", "rule-v1")
        ));

        GoldForecastEvaluation result = service.evaluate();

        assertThat(result.totalCount()).isEqualTo(4);
        assertThat(result.pendingCount()).isEqualTo(1);
        assertThat(result.resolvedCount()).isEqualTo(3);
        assertThat(result.overallAccuracy()).isEqualByComparingTo("0.6667");
        assertDirection(result.bullish(), ForecastDirection.BULLISH, 2, 1, "0.5000");
        assertDirection(result.neutral(), ForecastDirection.NEUTRAL, 1, 1, "1.0000");
        assertDirection(result.bearish(), ForecastDirection.BEARISH, 0, 0, null);
        assertThat(result.neutralBaselineAccuracy()).isEqualByComparingTo("0.3333");
    }

    @Test
    void rollingAccuracyUsesTwentyMostRecentlyResolvedRecords() {
        List<StoredGoldDirectionForecast> records = new ArrayList<>();

        // 最旧的一条未命中；其余 20 条较新记录全部命中。
        records.add(resolved(ForecastDirection.BEARISH, ForecastDirection.BULLISH,
                false, "glm-4.7", "prompt-v1", "rule-v1", 0));
        for (int day = 1; day <= 20; day++) {
            records.add(resolved(ForecastDirection.BULLISH, ForecastDirection.BULLISH,
                    true, "glm-4.7", "prompt-v1", "rule-v1", day));
        }
        // 故意反转输入，证明统计不能依赖仓储当前返回顺序。
        java.util.Collections.reverse(records);
        when(forecastRepository.findAllForEvaluation()).thenReturn(records);

        GoldForecastEvaluation result = service.evaluate();

        assertThat(result.overallAccuracy()).isEqualByComparingTo("0.9524");
        assertThat(result.rolling20Accuracy()).isEqualByComparingTo("1.0000");
    }

    @Test
    void keepsDifferentModelPromptAndRuleVersionsSeparate() {
        when(forecastRepository.findAllForEvaluation()).thenReturn(List.of(
                resolved(ForecastDirection.BULLISH, ForecastDirection.BULLISH,
                        true, "glm-4.7", "prompt-v1", "rule-v1", 3),
                resolved(ForecastDirection.BULLISH, ForecastDirection.BEARISH,
                        false, "glm-4.7", "prompt-v1", "rule-v1", 2),
                resolved(ForecastDirection.NEUTRAL, ForecastDirection.NEUTRAL,
                        true, "glm-4.7", "prompt-v2", "rule-v1", 1),
                resolved(ForecastDirection.BEARISH, ForecastDirection.BEARISH,
                        true, "glm-local", "prompt-v2", "rule-v2", 0)
        ));

        GoldForecastEvaluation result = service.evaluate();

        assertThat(result.versions()).containsExactly(
                new ForecastVersionEvaluation(
                        "glm-4.7", "prompt-v1", "rule-v1", 2, 1,
                        new BigDecimal("0.5000")
                ),
                new ForecastVersionEvaluation(
                        "glm-4.7", "prompt-v2", "rule-v1", 1, 1,
                        new BigDecimal("1.0000")
                ),
                new ForecastVersionEvaluation(
                        "glm-local", "prompt-v2", "rule-v2", 1, 1,
                        new BigDecimal("1.0000")
                )
        );
    }

    private void assertDirection(
            DirectionEvaluation actual,
            ForecastDirection direction,
            int sampleCount,
            int hitCount,
            String accuracy
    ) {
        assertThat(actual.direction()).isEqualTo(direction);
        assertThat(actual.sampleCount()).isEqualTo(sampleCount);
        assertThat(actual.hitCount()).isEqualTo(hitCount);
        if (accuracy == null) {
            assertThat(actual.accuracy()).isNull();
        } else {
            assertThat(actual.accuracy()).isEqualByComparingTo(accuracy);
        }
    }

    private StoredGoldDirectionForecast pending(
            String modelName,
            String promptVersion,
            String ruleVersion
    ) {
        return forecast(
                ForecastDirection.NEUTRAL, null, null, ForecastStatus.PENDING,
                modelName, promptVersion, ruleVersion, null
        );
    }

    private StoredGoldDirectionForecast resolved(
            ForecastDirection predicted,
            ForecastDirection actual,
            boolean hit,
            String modelName,
            String promptVersion,
            String ruleVersion,
            int resolvedDay
    ) {
        return forecast(
                predicted, actual, hit, ForecastStatus.RESOLVED,
                modelName, promptVersion, ruleVersion,
                OffsetDateTime.parse("2026-08-01T00:00:00Z").plusDays(resolvedDay)
        );
    }

    /** 固定记录仅用于验证统计规则，不代表真实预测或行情。 */
    private StoredGoldDirectionForecast forecast(
            ForecastDirection predicted,
            ForecastDirection actual,
            Boolean hit,
            ForecastStatus status,
            String modelName,
            String promptVersion,
            String ruleVersion,
            OffsetDateTime resolvedAt
    ) {
        boolean resolved = status == ForecastStatus.RESOLVED;
        return new StoredGoldDirectionForecast(
                UUID.randomUUID(), GoldForecastTestFixtures.SNAPSHOT_ID,
                LocalDate.parse("2026-07-31"), new BigDecimal("2500.000000"),
                predicted, "固定测试依据", List.of("固定测试失效条件"),
                modelName, promptVersion, "a".repeat(64), ruleVersion,
                "固定测试响应", status,
                resolved ? LocalDate.parse("2026-08-01") : null,
                resolved ? new BigDecimal("2510.000000") : null,
                resolved ? new BigDecimal("0.400000") : null,
                actual, hit, resolvedAt,
                OffsetDateTime.parse("2026-07-31T01:00:00Z")
        );
    }
}
