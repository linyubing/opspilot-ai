package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.GoldResearchNarrativeService;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import com.opspilot.ai.forecast.GoldForecastGenerationService;
import com.opspilot.ai.forecast.SaveGoldForecastResult;
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

    @Mock
    private GoldForecastGenerationService forecastService;

    private GoldDailyResearchReportService service;

    @BeforeEach
    void setUp() {
        service = new GoldDailyResearchReportService(
                preparationService,
                narrativeService,
                forecastService
        );
    }

    @Test
    @DisplayName("真实数据和快照准备完成后才生成大模型研究解读")
    void generatesNarrativeAfterPreparation() {
        GoldResearchPreparationResult preparation = preparationResult();
        SaveResearchNarrativeResult narrative =
                mock(SaveResearchNarrativeResult.class);
        SaveGoldForecastResult forecast =
                mock(SaveGoldForecastResult.class);
        when(preparationService.prepareDaily()).thenReturn(preparation);
        when(narrativeService.generate(SNAPSHOT_ID)).thenReturn(narrative);
        when(forecastService.generate(SNAPSHOT_ID)).thenReturn(forecast);

        GoldDailyResearchReportResult result = service.generateDailyReport();

        assertThat(result.preparation()).isSameAs(preparation);
        assertThat(result.narrative()).isSameAs(narrative);
        assertThat(result.forecast()).isSameAs(forecast);
        InOrder order = inOrder(
                preparationService,
                narrativeService,
                forecastService
        );
        order.verify(preparationService).prepareDaily();
        order.verify(narrativeService).generate(SNAPSHOT_ID);
        order.verify(forecastService).generate(SNAPSHOT_ID);
    }

    @Test
    @DisplayName("真实数据准备失败时不调用大模型")
    void skipsNarrativeWhenPreparationFails() {
        MacroDataUnavailableException failure =
                new MacroDataUnavailableException("实际利率暂时不可用");
        when(preparationService.prepareDaily()).thenThrow(failure);

        assertThatThrownBy(service::generateDailyReport).isSameAs(failure);
        verifyNoInteractions(narrativeService, forecastService);
    }

    @Test
    @DisplayName("研究解读生成失败时不生成方向预测")
    void skipsForecastWhenNarrativeFails() {
        GoldResearchPreparationResult preparation = preparationResult();
        RuntimeException failure = new RuntimeException("大模型解读失败");
        when(preparationService.prepareDaily()).thenReturn(preparation);
        when(narrativeService.generate(SNAPSHOT_ID)).thenThrow(failure);

        assertThatThrownBy(service::generateDailyReport).isSameAs(failure);
        verifyNoInteractions(forecastService);
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
