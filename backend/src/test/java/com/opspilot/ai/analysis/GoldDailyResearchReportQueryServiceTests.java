package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.ResearchNarrativeRepository;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.forecast.GoldForecastRepository;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldDailyResearchReportQueryServiceTests {

    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    private GoldResearchSnapshotRepository snapshotRepository;
    private ResearchNarrativeRepository narrativeRepository;
    private GoldForecastRepository forecastRepository;
    private GoldDailyResearchReportQueryService service;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(GoldResearchSnapshotRepository.class);
        narrativeRepository = mock(ResearchNarrativeRepository.class);
        forecastRepository = mock(GoldForecastRepository.class);
        service = new GoldDailyResearchReportQueryService(
                snapshotRepository,
                narrativeRepository,
                forecastRepository
        );
    }

    @Test
    @DisplayName("使用最新快照组装同一快照的完整黄金日报")
    void assemblesCompleteReportForLatestSnapshot() {
        StoredGoldResearchSnapshot snapshot = snapshot();
        StoredResearchNarrative narrative = mock(StoredResearchNarrative.class);
        StoredGoldDirectionForecast forecast =
                mock(StoredGoldDirectionForecast.class);
        when(snapshotRepository.findRecent(1)).thenReturn(List.of(snapshot));
        when(narrativeRepository.findLatestBySnapshotId(SNAPSHOT_ID))
                .thenReturn(Optional.of(narrative));
        when(forecastRepository.findLatestBySnapshotId(SNAPSHOT_ID))
                .thenReturn(Optional.of(forecast));

        StoredGoldDailyResearchReport report =
                service.findLatestCompleteReport();

        assertThat(report.snapshot()).isSameAs(snapshot);
        assertThat(report.narrative()).isSameAs(narrative);
        assertThat(report.forecast()).isSameAs(forecast);
    }

    @Test
    @DisplayName("没有研究快照时拒绝返回空日报")
    void rejectsMissingSnapshot() {
        when(snapshotRepository.findRecent(1)).thenReturn(List.of());

        assertThatThrownBy(service::findLatestCompleteReport)
                .isInstanceOf(GoldDailyResearchReportNotFoundException.class)
                .hasMessageContaining("研究快照");
    }

    @Test
    @DisplayName("最新快照缺少研究解读时拒绝返回不完整日报")
    void rejectsMissingNarrative() {
        when(snapshotRepository.findRecent(1)).thenReturn(List.of(snapshot()));
        when(narrativeRepository.findLatestBySnapshotId(SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(service::findLatestCompleteReport)
                .isInstanceOf(GoldDailyResearchReportNotFoundException.class)
                .hasMessageContaining("研究解读");
    }

    @Test
    @DisplayName("最新快照缺少方向预测时拒绝返回不完整日报")
    void rejectsMissingForecast() {
        StoredResearchNarrative narrative = mock(StoredResearchNarrative.class);
        when(snapshotRepository.findRecent(1)).thenReturn(List.of(snapshot()));
        when(narrativeRepository.findLatestBySnapshotId(SNAPSHOT_ID))
                .thenReturn(Optional.of(narrative));
        when(forecastRepository.findLatestBySnapshotId(SNAPSHOT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(service::findLatestCompleteReport)
                .isInstanceOf(GoldDailyResearchReportNotFoundException.class)
                .hasMessageContaining("方向预测");
    }

    private StoredGoldResearchSnapshot snapshot() {
        return new StoredGoldResearchSnapshot(
                SNAPSHOT_ID,
                mock(GoldResearchSnapshot.class),
                OffsetDateTime.parse("2026-08-27T01:00:00Z")
        );
    }
}
