package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.GoldResearchSnapshotNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldForecastGenerationServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final String MODEL_NAME = "glm-4.7";

    @Mock
    private GoldResearchSnapshotRepository snapshotRepository;
    @Mock
    private GoldForecastRepository forecastRepository;
    @Mock
    private GoldForecastPromptBuilder promptBuilder;
    @Mock
    private GoldForecastGateway gateway;
    @Mock
    private GoldForecastValidator validator;

    private GoldForecastGenerationService service;

    @BeforeEach
    void setUp() {
        service = new GoldForecastGenerationService(
                snapshotRepository,
                forecastRepository,
                promptBuilder,
                gateway,
                validator,
                new GoldForecastProperties(MODEL_NAME),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsMissingSnapshotBeforeOtherWork() {
        when(snapshotRepository.findById(GoldForecastTestFixtures.SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(GoldForecastTestFixtures.SNAPSHOT_ID))
                .isInstanceOf(GoldResearchSnapshotNotFoundException.class);

        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    @Test
    void rejectsNonOfficialSnapshotBeforeIdempotencyLookup() {
        StoredGoldResearchSnapshot snapshot = snapshotWithVersion("gold-real-rate-v1");
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastSnapshotException.class)
                .hasMessageContaining("gold-real-rate-v1");

        verifyNoInteractions(forecastRepository, promptBuilder, gateway, validator);
    }

    @Test
    void returnsExistingForecastWithoutBuildingPromptOrCallingModel() {
        StoredGoldResearchSnapshot snapshot = GoldForecastTestFixtures.snapshot("2515.75");
        StoredGoldDirectionForecast existing = existingForecast(snapshot);
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        when(forecastRepository.findByKey(
                snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.PROMPT_VERSION,
                GoldForecastRule.RULE_VERSION
        )).thenReturn(Optional.of(existing));

        SaveGoldForecastResult result = service.generate(snapshot.id());

        assertThat(result).isEqualTo(new SaveGoldForecastResult(existing, false));
        verifyNoInteractions(promptBuilder, gateway, validator);
        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void generatesValidatesAndSavesPendingForecastInOrder() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        GoldForecastPrompt prompt = prompt();
        GeneratedGoldForecast generated = generated();
        when(promptBuilder.build(snapshot)).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(generated);
        when(forecastRepository.saveIfAbsent(any())).thenAnswer(invocation ->
                new SaveGoldForecastResult(invocation.getArgument(0), true)
        );

        SaveGoldForecastResult result = service.generate(snapshot.id());

        InOrder order = inOrder(
                snapshotRepository, forecastRepository,
                promptBuilder, gateway, validator
        );
        order.verify(snapshotRepository).findById(snapshot.id());
        order.verify(forecastRepository).findByKey(
                snapshot.id(), MODEL_NAME, prompt.version(), GoldForecastRule.RULE_VERSION
        );
        order.verify(promptBuilder).build(snapshot);
        order.verify(gateway).generate(prompt);
        order.verify(validator).validate(generated.content());
        order.verify(forecastRepository).saveIfAbsent(any());

        ArgumentCaptor<StoredGoldDirectionForecast> captor =
                ArgumentCaptor.forClass(StoredGoldDirectionForecast.class);
        verify(forecastRepository).saveIfAbsent(captor.capture());
        StoredGoldDirectionForecast candidate = captor.getValue();
        assertThat(result.created()).isTrue();
        assertThat(candidate.snapshotId()).isEqualTo(snapshot.id());
        assertThat(candidate.baseDate()).isEqualTo(snapshot.snapshot().latestGoldDate());
        assertThat(candidate.basePrice()).isEqualByComparingTo("2515.75");
        assertThat(candidate.predictedDirection()).isEqualTo(ForecastDirection.BULLISH);
        assertThat(candidate.reasoning()).isEqualTo("双因子共同支持黄金。");
        assertThat(candidate.invalidationConditions()).containsExactly("实际利率转为持续上行");
        assertThat(candidate.modelName()).isEqualTo(MODEL_NAME);
        assertThat(candidate.promptVersion()).isEqualTo(prompt.version());
        assertThat(candidate.promptHash()).isEqualTo(prompt.sha256());
        assertThat(candidate.forecastRuleVersion()).isEqualTo(GoldForecastRule.RULE_VERSION);
        assertThat(candidate.rawResponse()).isEqualTo(generated.rawResponse());
        assertThat(candidate.status()).isEqualTo(ForecastStatus.PENDING);
        assertThat(candidate.targetDate()).isNull();
        assertThat(candidate.targetPrice()).isNull();
        assertThat(candidate.actualReturn()).isNull();
        assertThat(candidate.actualDirection()).isNull();
        assertThat(candidate.hit()).isNull();
        assertThat(candidate.resolvedAt()).isNull();
        assertThat(candidate.createdAt()).isEqualTo(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void doesNotSaveWhenModelCallFails() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(snapshot)).thenReturn(prompt());
        when(gateway.generate(prompt())).thenThrow(
                new GoldForecastAiUnavailableException("上游不可用", new IllegalStateException())
        );

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(GoldForecastAiUnavailableException.class);

        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void doesNotSaveWhenModelResponseCannotBeParsed() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(snapshot)).thenReturn(prompt());
        when(gateway.generate(prompt())).thenThrow(
                new InvalidGoldForecastAiResponseException("响应不是合法 JSON", new IllegalArgumentException())
        );

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(InvalidGoldForecastAiResponseException.class);

        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    @Test
    void doesNotSaveWhenForecastViolatesSafetyRules() {
        StoredGoldResearchSnapshot snapshot = prepareNewForecast();
        when(promptBuilder.build(snapshot)).thenReturn(prompt());
        when(gateway.generate(prompt())).thenReturn(generated());
        doThrow(new UnsafeGoldForecastException("包含交易建议"))
                .when(validator).validate(generated().content());

        assertThatThrownBy(() -> service.generate(snapshot.id()))
                .isInstanceOf(UnsafeGoldForecastException.class);

        verify(forecastRepository, never()).saveIfAbsent(any());
    }

    private StoredGoldResearchSnapshot prepareNewForecast() {
        StoredGoldResearchSnapshot snapshot = GoldForecastTestFixtures.snapshot("2515.75");
        when(snapshotRepository.findById(snapshot.id())).thenReturn(Optional.of(snapshot));
        when(forecastRepository.findByKey(
                snapshot.id(), MODEL_NAME,
                GoldForecastPromptBuilder.PROMPT_VERSION,
                GoldForecastRule.RULE_VERSION
        )).thenReturn(Optional.empty());
        return snapshot;
    }

    private GoldForecastPrompt prompt() {
        return new GoldForecastPrompt(
                GoldForecastPromptBuilder.PROMPT_VERSION,
                "正式黄金方向预测提示词",
                "a".repeat(64)
        );
    }

    private GeneratedGoldForecast generated() {
        return new GeneratedGoldForecast(
                MODEL_NAME,
                "{\"direction\":\"BULLISH\"}",
                new GoldDirectionForecastContent(
                        ForecastDirection.BULLISH,
                        "双因子共同支持黄金。",
                        List.of("实际利率转为持续上行")
                )
        );
    }

    private StoredGoldResearchSnapshot snapshotWithVersion(String version) {
        StoredGoldResearchSnapshot source = GoldForecastTestFixtures.snapshot("2515.75");
        GoldResearchSnapshot value = source.snapshot();
        return new StoredGoldResearchSnapshot(
                source.id(),
                new GoldResearchSnapshot(
                        value.analysisDate(), value.latestGoldDate(),
                        value.latestRealRateDate(), value.latestDollarIndexDate(),
                        value.gold(), value.realRate(), value.dollarIndex(),
                        value.realRateAssessment(), value.dollarIndexAssessment(),
                        version, value.disclaimer()
                ),
                source.createdAt()
        );
    }

    private StoredGoldDirectionForecast existingForecast(
            StoredGoldResearchSnapshot snapshot
    ) {
        return new StoredGoldDirectionForecast(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                snapshot.id(), snapshot.snapshot().latestGoldDate(),
                new BigDecimal("2515.75"), ForecastDirection.NEUTRAL,
                "等待更多证据。", List.of("因子方向发生明显变化"),
                MODEL_NAME, GoldForecastPromptBuilder.PROMPT_VERSION,
                "a".repeat(64), GoldForecastRule.RULE_VERSION,
                "{\"direction\":\"NEUTRAL\"}", ForecastStatus.PENDING,
                null, null, null, null, null, null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }
}
