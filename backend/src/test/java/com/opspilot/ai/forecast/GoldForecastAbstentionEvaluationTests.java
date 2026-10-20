package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.GoldSessionCheckResult;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 固定夹具只验证弃权分母，不构成真实模型成绩。 */
class GoldForecastAbstentionEvaluationTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void abstentionKeepsResolvedCountButDoesNotBecomeIncorrectDirection() {
        var repository = mock(GoldForecastRepository.class);
        when(repository.findAllForEvaluation()).thenReturn(List.of(
                row(ForecastSignal.PREDICTED, ForecastDirection.BULLISH, ForecastDirection.BULLISH, true, "new", 0),
                row(ForecastSignal.ABSTAINED, null, ForecastDirection.NEUTRAL, null, "new", 1),
                row(null, ForecastDirection.BEARISH, ForecastDirection.BEARISH, true, "old", 2),
                row(null, null, ForecastDirection.NEUTRAL, null, "old", 3)));
        var evaluation = new GoldForecastEvaluationService(repository).evaluate();
        assertThat(evaluation.resolvedCount()).isEqualTo(4);
        assertThat(evaluation.overallAccuracy()).isEqualByComparingTo("1");
        assertThat(evaluation.rolling20Accuracy()).isEqualByComparingTo("1");
        assertThat(evaluation.neutralBaselineAccuracy()).isEqualByComparingTo("0");
        JsonNode json = mapper.valueToTree(evaluation);
        assertThat(json.path("signals").path("signalCount").asInt(-1)).isEqualTo(2);
        assertThat(json.path("signals").path("declaredSignalCount").asInt(-1)).isEqualTo(1);
        assertThat(json.path("signals").path("abstainedCount").asInt(-1)).isEqualTo(1);
        assertThat(json.path("signals").path("legacyCount").asInt(-1)).isEqualTo(2);
        assertThat(json.path("signals").path("coverage").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(json.path("signals").path("signalAccuracy").decimalValue()).isEqualByComparingTo("1");
        assertThat(json.path("signals").path("neutralBaselineAccuracy").decimalValue()).isEqualByComparingTo("0");
        assertThat(json.path("signals").path("fullSampleAccuracy").isNull()).isTrue();
        assertThat(json.path("signals").path("note").asText()).contains("概率");
        var current = evaluation.versions().stream().filter(v -> v.promptVersion().equals("new")).findFirst().orElseThrow();
        assertThat(current.accuracy()).isEqualByComparingTo("1");
        assertThat(current.sampleCount()).isEqualTo(1);
        JsonNode version = mapper.valueToTree(current);
        assertThat(version.path("signals").path("abstainedCount").asInt(-1)).isEqualTo(1);
        assertThat(evaluation.timing().inSession().accuracy()).isEqualByComparingTo("1");
    }

    @Test
    void earliestAbstentionRemainsSelectedAndResolvedWithoutManufacturedBaseline() {
        var first = row(ForecastSignal.ABSTAINED, null, ForecastDirection.NEUTRAL, null, "new", 0);
        var later = row(ForecastSignal.PREDICTED, ForecastDirection.NEUTRAL, ForecastDirection.NEUTRAL, true, "new", 0);
        var result = GoldTargetEvaluation.from(List.of(later, first));
        var group = result.groups().getFirst();
        assertThat(group.selectedCount()).isEqualTo(1);
        assertThat(group.resolvedCount()).isEqualTo(1);
        assertThat(group.unresolvedCount()).isZero();
        assertThat(group.duplicatesIgnored()).isEqualTo(1);
        assertThat(group.accuracy()).isNull();
        assertThat(group.neutralBaselineAccuracy()).isNull();
        JsonNode json = mapper.valueToTree(group);
        assertThat(json.path("signalCount").asInt(-1)).isZero();
        assertThat(json.path("abstainedCount").asInt(-1)).isEqualTo(1);
        assertThat(json.path("coverage").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void noSignalsLeavesAllDirectionalAccuraciesUnavailable() {
        var repository = mock(GoldForecastRepository.class);
        when(repository.findAllForEvaluation()).thenReturn(List.of(
                row(ForecastSignal.ABSTAINED, null, ForecastDirection.NEUTRAL, null, "new", 0)));
        var result = new GoldForecastEvaluationService(repository).evaluate();
        assertThat(result.resolvedCount()).isEqualTo(1);
        assertThat(result.overallAccuracy()).isNull();
        assertThat(result.rolling20Accuracy()).isNull();
        assertThat(result.neutralBaselineAccuracy()).isNull();
        assertThat(result.versions().getFirst().accuracy()).isNull();
        assertThat(result.timing().inSession().accuracy()).isNull();
    }

    @Test
    void fullScoreIsAvailableOnlyWhenEveryRecordExplicitlyHasResolvedSignal() {
        var repository = mock(GoldForecastRepository.class);
        when(repository.findAllForEvaluation()).thenReturn(List.of(
                row(ForecastSignal.PREDICTED, ForecastDirection.BULLISH, ForecastDirection.BULLISH, true, "new", 0)));
        JsonNode json = mapper.valueToTree(new GoldForecastEvaluationService(repository).evaluate());
        assertThat(json.path("signals").path("coverage").decimalValue()).isEqualByComparingTo("1");
        assertThat(json.path("signals").path("fullSampleAccuracy").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    void versionCoverageIncludesPendingAbstentionFromSameProtocol() {
        var repository = mock(GoldForecastRepository.class);
        var abstained = row(ForecastSignal.ABSTAINED, null, ForecastDirection.NEUTRAL, null, "new", 1);
        var pending = new StoredGoldDirectionForecast(abstained.id(), abstained.snapshotId(), abstained.baseDate(),
                abstained.basePrice(), null, abstained.reasoning(), abstained.invalidationConditions(), abstained.modelName(),
                abstained.promptVersion(), abstained.promptHash(), abstained.forecastRuleVersion(), abstained.rawResponse(),
                ForecastStatus.PENDING, null, null, null, null, null, null, abstained.createdAt(), abstained.timing(),
                ForecastSignal.ABSTAINED);
        when(repository.findAllForEvaluation()).thenReturn(List.of(pending,
                row(ForecastSignal.PREDICTED, ForecastDirection.BULLISH, ForecastDirection.BULLISH, true, "new", 0)));
        var result = new GoldForecastEvaluationService(repository).evaluate();
        var version = result.versions().getFirst();
        assertThat(version.sampleCount()).isEqualTo(1);
        assertThat(version.hitCount()).isEqualTo(1);
        assertThat(version.accuracy()).isEqualByComparingTo("1");
        JsonNode json = mapper.valueToTree(version);
        assertThat(json.path("signals").path("sampleCount").asInt(-1)).isEqualTo(2);
        assertThat(json.path("signals").path("abstainedCount").asInt(-1)).isEqualTo(1);
        assertThat(json.path("signals").path("coverage").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(json.path("signals").path("fullSampleAccuracy").isNull()).isTrue();
    }

    private StoredGoldDirectionForecast row(ForecastSignal signal, ForecastDirection direction,
            ForecastDirection actual, Boolean hit, String prompt, int day) {
        var base = LocalDate.of(2026, 8, 26).plusDays(day);
        var target = GoldSession.forDate(base.plusDays(1));
        var timing = new GoldForecastTiming(target.date(), target.start(), target.end(), GoldSessionCheckResult.RULE_VERSION);
        var created = OffsetDateTime.ofInstant(target.start().plusSeconds(signal == ForecastSignal.ABSTAINED ? 1 : 2), ZoneOffset.UTC);
        return new StoredGoldDirectionForecast(UUID.randomUUID(), UUID.randomUUID(), base,
                new BigDecimal("2500"), direction, "固定测试依据", List.of("固定失效条件"), "model",
                prompt, "a".repeat(64), "rule", "测试响应", ForecastStatus.RESOLVED, target.date(),
                new BigDecimal("2500"), BigDecimal.ZERO, actual, hit,
                OffsetDateTime.ofInstant(target.end().plusSeconds(1), ZoneOffset.UTC), created, timing, signal);
    }
}
