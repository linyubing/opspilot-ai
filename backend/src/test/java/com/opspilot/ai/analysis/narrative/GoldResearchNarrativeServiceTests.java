package com.opspilot.ai.analysis.narrative;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldResearchNarrativeServiceTests {

    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "0da5c4c6-81e0-47e8-b016-b9c070830946"
    );
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Mock
    private GoldResearchSnapshotRepository snapshotRepository;
    @Mock
    private ResearchNarrativeRepository narrativeRepository;
    @Mock
    private ResearchNarrativePromptBuilder promptBuilder;
    @Mock
    private ResearchNarrativeGateway gateway;
    @Mock
    private ResearchNarrativeValidator validator;

    private GoldResearchNarrativeService service;

    @BeforeEach
    void setUp() {
        service = new GoldResearchNarrativeService(
                snapshotRepository,
                narrativeRepository,
                promptBuilder,
                gateway,
                validator,
                new ResearchNarrativeProperties("glm-4.7"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void rejectsMissingSnapshotBeforeAnyOtherWork() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(SNAPSHOT_ID))
                .isInstanceOf(GoldResearchSnapshotNotFoundException.class)
                .hasMessageContaining(SNAPSHOT_ID.toString());

        verifyNoInteractions(narrativeRepository, promptBuilder, gateway, validator);
    }

    @Test
    void returnsExistingRecordWithoutCallingModel() {
        StoredGoldResearchSnapshot snapshot = snapshot();
        StoredResearchNarrative existing = storedNarrative();
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot));
        when(narrativeRepository.findByKey(
                SNAPSHOT_ID,
                "glm-4.7",
                ResearchNarrativePromptBuilder.PROMPT_VERSION
        )).thenReturn(Optional.of(existing));

        SaveResearchNarrativeResult result = service.generate(SNAPSHOT_ID);

        assertThat(result).isEqualTo(
                new SaveResearchNarrativeResult(existing, false)
        );
        verifyNoInteractions(promptBuilder, gateway, validator);
        verify(narrativeRepository, never()).saveIfAbsent(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void generatesValidatesAndSavesNewNarrativeInOrder() {
        StoredGoldResearchSnapshot snapshot = snapshot();
        ResearchNarrativePrompt prompt = prompt();
        GeneratedResearchNarrative generated = generated();
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot));
        when(narrativeRepository.findByKey(
                SNAPSHOT_ID, "glm-4.7", prompt.version()
        )).thenReturn(Optional.empty());
        when(promptBuilder.build(snapshot)).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(generated);
        when(narrativeRepository.saveIfAbsent(
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> new SaveResearchNarrativeResult(
                invocation.getArgument(0), true
        ));

        SaveResearchNarrativeResult result = service.generate(SNAPSHOT_ID);

        InOrder order = inOrder(
                snapshotRepository,
                narrativeRepository,
                promptBuilder,
                gateway,
                validator
        );
        order.verify(snapshotRepository).findById(SNAPSHOT_ID);
        order.verify(narrativeRepository).findByKey(
                SNAPSHOT_ID, "glm-4.7", prompt.version()
        );
        order.verify(promptBuilder).build(snapshot);
        order.verify(gateway).generate(prompt);
        order.verify(validator).validate(generated.content());
        order.verify(narrativeRepository).saveIfAbsent(
                org.mockito.ArgumentMatchers.any()
        );

        ArgumentCaptor<StoredResearchNarrative> captor =
                ArgumentCaptor.forClass(StoredResearchNarrative.class);
        verify(narrativeRepository).saveIfAbsent(captor.capture());
        StoredResearchNarrative candidate = captor.getValue();
        assertThat(result.created()).isTrue();
        assertThat(candidate.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(candidate.modelName()).isEqualTo("glm-4.7");
        assertThat(candidate.promptVersion()).isEqualTo(prompt.version());
        assertThat(candidate.promptHash()).isEqualTo(prompt.sha256());
        assertThat(candidate.rawResponse()).isEqualTo(generated.rawResponse());
        assertThat(candidate.createdAt()).isEqualTo(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void doesNotSaveWhenModelFails() {
        prepareNewNarrative();
        when(gateway.generate(prompt()))
                .thenThrow(new ResearchAiUnavailableException(
                        "上游不可用", new IllegalStateException()
                ));

        assertThatThrownBy(() -> service.generate(SNAPSHOT_ID))
                .isInstanceOf(ResearchAiUnavailableException.class);

        verify(narrativeRepository, never()).saveIfAbsent(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void doesNotSaveWhenValidationFails() {
        prepareNewNarrative();
        when(gateway.generate(prompt())).thenReturn(generated());
        org.mockito.Mockito.doThrow(
                new UnsafeResearchNarrativeException("越界内容")
        ).when(validator).validate(generated().content());

        assertThatThrownBy(() -> service.generate(SNAPSHOT_ID))
                .isInstanceOf(UnsafeResearchNarrativeException.class);

        verify(narrativeRepository, never()).saveIfAbsent(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void verifiesSnapshotBeforeReadingHistory() {
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(narrativeRepository.findBySnapshotId(SNAPSHOT_ID))
                .thenReturn(List.of(storedNarrative()));

        assertThat(service.findBySnapshotId(SNAPSHOT_ID))
                .containsExactly(storedNarrative());
    }

    private void prepareNewNarrative() {
        StoredGoldResearchSnapshot snapshot = snapshot();
        when(snapshotRepository.findById(SNAPSHOT_ID))
                .thenReturn(Optional.of(snapshot));
        when(narrativeRepository.findByKey(
                SNAPSHOT_ID,
                "glm-4.7",
                ResearchNarrativePromptBuilder.PROMPT_VERSION
        )).thenReturn(Optional.empty());
        when(promptBuilder.build(snapshot)).thenReturn(prompt());
    }

    private ResearchNarrativePrompt prompt() {
        return new ResearchNarrativePrompt(
                ResearchNarrativePromptBuilder.PROMPT_VERSION,
                "正式研究提示词",
                "a".repeat(64)
        );
    }

    private GeneratedResearchNarrative generated() {
        return new GeneratedResearchNarrative(
                "glm-4.7", "raw-json", content()
        );
    }

    private StoredResearchNarrative storedNarrative() {
        return new StoredResearchNarrative(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                SNAPSHOT_ID, content(), "glm-4.7",
                ResearchNarrativePromptBuilder.PROMPT_VERSION,
                "a".repeat(64), "raw-json",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    private ResearchNarrativeContent content() {
        return new ResearchNarrativeContent(
                "双因子摘要", "实际利率分析", "美元指数分析",
                List.of("日期差异"), List.of("继续观察"),
                "不构成价格预测、交易或投资建议"
        );
    }

    /** 固定领域对象只验证编排顺序，不代表真实行情或研究结论。 */
    private StoredGoldResearchSnapshot snapshot() {
        OffsetDateTime collectedAt = OffsetDateTime.ofInstant(
                NOW.minusSeconds(3600), ZoneOffset.UTC
        );
        LocalDate date = LocalDate.parse("2026-08-27");
        GoldResearchSnapshot value = new GoldResearchSnapshot(
                date, date, date, date,
                new GoldReturnMetrics(
                        new BigDecimal("2500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, collectedAt
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.4"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, collectedAt
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, collectedAt
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "real-rate-v1", "中性"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL, "dollar-v1", "中性"
                ),
                "gold-multifactor-v2", "不构成投资建议"
        );
        return new StoredGoldResearchSnapshot(SNAPSHOT_ID, value, collectedAt);
    }
}
