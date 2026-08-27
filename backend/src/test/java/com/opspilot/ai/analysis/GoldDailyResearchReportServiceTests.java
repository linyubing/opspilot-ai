package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.GoldResearchNarrativeService;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.MacroDataUnavailableException;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证每日黄金研究报告的数据准备、模型调用顺序和失败边界。 */
@ExtendWith(MockitoExtension.class)
class GoldDailyResearchReportServiceTests {

    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    @Mock
    private GoldResearchPreparationService preparationService;

    @Mock
    private GoldResearchNarrativeService narrativeService;

    private GoldDailyResearchReportService service;

    @BeforeEach
    void setUp() {
        service = new GoldDailyResearchReportService(
                preparationService,
                narrativeService
        );
    }

    @Test
    @DisplayName("真实数据和快照准备完成后才生成大模型研究解读")
    void generatesNarrativeAfterPreparation() {
        GoldResearchPreparationResult preparation = preparationResult();
        SaveResearchNarrativeResult narrative =
                mock(SaveResearchNarrativeResult.class);
        when(preparationService.prepareDaily()).thenReturn(preparation);
        when(narrativeService.generate(SNAPSHOT_ID)).thenReturn(narrative);

        GoldDailyResearchReportResult result = service.generateDailyReport();

        assertThat(result.preparation()).isSameAs(preparation);
        assertThat(result.narrative()).isSameAs(narrative);
        InOrder order = inOrder(preparationService, narrativeService);
        order.verify(preparationService).prepareDaily();
        order.verify(narrativeService).generate(SNAPSHOT_ID);
    }

    @Test
    @DisplayName("真实数据准备失败时不调用大模型")
    void skipsNarrativeWhenPreparationFails() {
        MacroDataUnavailableException failure =
                new MacroDataUnavailableException("实际利率暂时不可用");
        when(preparationService.prepareDaily()).thenThrow(failure);

        assertThatThrownBy(service::generateDailyReport).isSameAs(failure);
        verifyNoInteractions(narrativeService);
    }

    private GoldResearchPreparationResult preparationResult() {
        StoredGoldResearchSnapshot snapshot =
                mock(StoredGoldResearchSnapshot.class);
        when(snapshot.id()).thenReturn(SNAPSHOT_ID);

        return new GoldResearchPreparationResult(
                mock(GoldPriceSyncResult.class),
                mock(RealRateSyncResult.class),
                mock(DollarIndexSyncResult.class),
                new SaveGoldResearchSnapshotResult(snapshot, true)
        );
    }
}
