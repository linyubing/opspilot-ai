package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.api.GoldForecastResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 数学记录仅核验发布资格分层，不是市场模型效果样本。 */
class GoldForecastTimingEvaluationTests {
    private final GoldForecastRepository repository = mock(GoldForecastRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test @DisplayName("未知、候选开盘前、候选盘中与错日记录分开，不能计为可信预测")
    void separatesTimingGroups() {
        var before = timing("2026-08-31", "2026-08-30T21:00:00Z", "2026-08-31T21:00:00Z");
        var during = timing("2026-08-27", "2026-08-26T21:00:00Z", "2026-08-27T21:00:00Z");
        when(repository.findAllForEvaluation()).thenReturn(List.of(
                record("2026-08-26", "2026-08-27", "2026-08-27T08:00:00Z", true, null),
                record("2026-08-28", "2026-08-31", "2026-08-29T12:00:00Z", true, before),
                record("2026-08-26", "2026-08-27", "2026-08-27T08:00:00Z", false, during),
                record("2026-08-26", "2026-08-28", "2026-08-27T08:00:00Z", true, during),
                record("2026-08-26", "2026-08-27", "2026-08-27T21:00:00Z", true, during)));
        JsonNode json = mapper.valueToTree(new GoldForecastEvaluationService(repository).evaluate());
        assertThat(json.path("timing").isMissingNode()).isFalse();
        var groups = json.path("timing");
        assertThat(groups.path("unknown").path("sampleCount").asInt()).isEqualTo(1);
        assertThat(groups.path("beforeSession").path("sampleCount").asInt()).isEqualTo(1);
        assertThat(groups.path("beforeSession").path("accuracy").decimalValue()).isEqualByComparingTo("1.0000");
        assertThat(groups.path("inSession").path("sampleCount").asInt()).isEqualTo(1);
        assertThat(groups.path("inSession").path("accuracy").decimalValue()).isEqualByComparingTo("0.0000");
        assertThat(groups.path("invalid").path("sampleCount").asInt()).isEqualTo(2);
        assertThat(groups.path("trustedCount").asInt(-1)).isZero();
        assertThat(groups.path("trustedAccuracy").isNull()).isTrue();
    }

    @Test @DisplayName("持久化承诺日优先，日历后来变化不能改历史目标")
    void preservesPromisedDateInResponse() {
        var t = timing("2026-08-27", "2026-08-26T21:00:00Z", "2026-08-27T21:00:00Z");
        var record = record("2026-08-26", "2026-08-27", "2026-08-27T08:00:00Z", true, t);
        var response = GoldForecastResponse.from(record, null, date -> LocalDate.parse("2026-08-28"));
        assertThat(response.expectedTargetDate()).isEqualTo("2026-08-27");
        var json = mapper.valueToTree(response);
        assertThat(json.path("publicationPhase").asText()).isEqualTo("IN_SESSION");
        assertThat(json.path("publicationWarning").asText()).contains("候选", "不能");
    }

    @Test @DisplayName("旧记录不补推断发布时间资格，响应明确未知")
    void keepsLegacyUnknown() {
        var response = GoldForecastResponse.from(record("2026-08-26", "2026-08-27", "2026-08-27T08:00:00Z", true, null));
        var json = mapper.valueToTree(response);
        assertThat(json.path("publicationPhase").asText()).isEqualTo("UNKNOWN");
        assertThat(json.path("timing").isNull()).isTrue();
        assertThat(json.path("publicationWarning").asText()).contains("未知");
    }

    private GoldForecastTiming timing(String date, String start, String end) {
        return new GoldForecastTiming(LocalDate.parse(date), Instant.parse(start), Instant.parse(end), "sydney-0700-candidate-v1");
    }

    private StoredGoldDirectionForecast record(String base, String target, String created, boolean hit, GoldForecastTiming timing) {
        return new StoredGoldDirectionForecast(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse(base), BigDecimal.TEN,
                ForecastDirection.NEUTRAL, "数学测试", List.of(), "test-model", "test-prompt", "a".repeat(64), "test-rule", "test-json",
                ForecastStatus.RESOLVED, LocalDate.parse(target), BigDecimal.TEN, BigDecimal.ZERO, ForecastDirection.NEUTRAL,
                hit, OffsetDateTime.parse("2026-09-02T12:00:00Z"), OffsetDateTime.parse(created), timing);
    }
}
