package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoldResearchSnapshotRecordingServiceTests {

    private static final Instant NOW =
            Instant.parse("2026-08-27T01:00:00Z");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private GoldResearchSnapshotService snapshotService;
    private GoldResearchSnapshotRepository repository;
    private GoldResearchSnapshotRecordingService service;

    @BeforeEach
    void setUp() {
        snapshotService = mock(GoldResearchSnapshotService.class);
        repository = mock(GoldResearchSnapshotRepository.class);
        service = new GoldResearchSnapshotRecordingService(
                snapshotService,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("生成当前快照后使用统一时钟正式留痕")
    void recordsCurrentSnapshot() {
        GoldResearchSnapshot snapshot = snapshot();
        SaveGoldResearchSnapshotResult expected = saveResult(snapshot);
        when(snapshotService.createSnapshot()).thenReturn(snapshot);
        when(repository.saveIfAbsent(snapshot, CREATED_AT))
                .thenReturn(expected);

        SaveGoldResearchSnapshotResult actual =
                service.recordCurrentSnapshot();

        assertThat(actual).isSameAs(expected);
        verify(repository).saveIfAbsent(snapshot, CREATED_AT);
    }

    @Test
    @DisplayName("快照生成失败时不写入历史仓储")
    void doesNotSaveWhenSnapshotCreationFails() {
        IllegalStateException failure =
                new IllegalStateException("研究数据暂不可用");
        when(snapshotService.createSnapshot()).thenThrow(failure);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                service::recordCurrentSnapshot
        ).isSameAs(failure);

        verifyNoInteractions(repository);
    }

    private SaveGoldResearchSnapshotResult saveResult(
            GoldResearchSnapshot snapshot
    ) {
        StoredGoldResearchSnapshot record =
                new StoredGoldResearchSnapshot(
                        UUID.fromString(
                                "11111111-1111-1111-1111-111111111111"
                        ),
                        snapshot,
                        CREATED_AT
                );

        return new SaveGoldResearchSnapshotResult(record, true);
    }

    /**
     * 固定数值只验证服务编排，不代表真实行情或研究结论。
     */
    private GoldResearchSnapshot snapshot() {
        OffsetDateTime collectedAt = CREATED_AT.minusHours(1);

        return new GoldResearchSnapshot(
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-25"),
                LocalDate.parse("2026-08-24"),
                new GoldReturnMetrics(
                        new BigDecimal("2500.00"),
                        new BigDecimal("0.1000"),
                        new BigDecimal("1.2000"),
                        new BigDecimal("2.3000"),
                        collectedAt
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.380000"),
                        new BigDecimal("-0.020000"),
                        new BigDecimal("-0.060000"),
                        new BigDecimal("-0.060000"),
                        new BigDecimal("-2.00"),
                        new BigDecimal("-6.00"),
                        new BigDecimal("-6.00"),
                        collectedAt
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "gold-real-rate-v1",
                        "实际利率变化有限，单因子状态为中性。"
                ),
                "单因子状态不构成投资建议。"
        );
    }
}
