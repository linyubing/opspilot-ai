package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRecordingService;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.DollarIndexSyncService;
import com.opspilot.ai.macrodata.MacroDataUnavailableException;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncService;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证每日研究准备的数据同步顺序和失败中断边界。 */
@ExtendWith(MockitoExtension.class)
class GoldResearchPreparationServiceTests {

    @Mock
    private GoldPriceSyncService goldPriceSyncService;

    @Mock
    private RealRateSyncService realRateSyncService;

    @Mock
    private DollarIndexSyncService dollarIndexSyncService;

    @Mock
    private GoldResearchSnapshotRecordingService recordingService;

    private GoldResearchPreparationService service;

    @BeforeEach
    void setUp() {
        service = new GoldResearchPreparationService(
                goldPriceSyncService,
                realRateSyncService,
                dollarIndexSyncService,
                recordingService
        );
    }

    @Test
    @DisplayName("三类数据全部同步成功后生成并保存研究快照")
    void recordsSnapshotAfterAllDataIsSynchronized() {
        GoldPriceSyncResult gold = goldResult();
        RealRateSyncResult realRate = realRateResult();
        DollarIndexSyncResult dollarIndex = dollarIndexResult();
        SaveGoldResearchSnapshotResult snapshot =
                mock(SaveGoldResearchSnapshotResult.class);
        when(goldPriceSyncService.syncDailyPrices()).thenReturn(gold);
        when(realRateSyncService.syncDailyObservations()).thenReturn(realRate);
        when(dollarIndexSyncService.syncDailyObservations())
                .thenReturn(dollarIndex);
        when(recordingService.recordCurrentSnapshot()).thenReturn(snapshot);

        GoldResearchPreparationResult result = service.prepareDaily();

        assertThat(result.goldPriceSync()).isSameAs(gold);
        assertThat(result.realRateSync()).isSameAs(realRate);
        assertThat(result.dollarIndexSync()).isSameAs(dollarIndex);
        assertThat(result.snapshot()).isSameAs(snapshot);
        InOrder order = inOrder(
                goldPriceSyncService,
                realRateSyncService,
                dollarIndexSyncService,
                recordingService
        );
        order.verify(goldPriceSyncService).syncDailyPrices();
        order.verify(realRateSyncService).syncDailyObservations();
        order.verify(dollarIndexSyncService).syncDailyObservations();
        order.verify(recordingService).recordCurrentSnapshot();
    }

    @Test
    @DisplayName("黄金行情同步失败时不执行宏观同步和快照保存")
    void stopsWhenGoldPriceSyncFails() {
        MarketDataUnavailableException failure =
                new MarketDataUnavailableException("黄金行情暂时不可用");
        when(goldPriceSyncService.syncDailyPrices()).thenThrow(failure);

        assertThatThrownBy(service::prepareDaily).isSameAs(failure);
        verifyNoInteractions(
                realRateSyncService,
                dollarIndexSyncService,
                recordingService
        );
    }

    @Test
    @DisplayName("实际利率同步失败时不执行美元指数同步和快照保存")
    void stopsWhenRealRateSyncFails() {
        when(goldPriceSyncService.syncDailyPrices()).thenReturn(goldResult());
        MacroDataUnavailableException failure =
                new MacroDataUnavailableException("实际利率暂时不可用");
        when(realRateSyncService.syncDailyObservations()).thenThrow(failure);

        assertThatThrownBy(service::prepareDaily).isSameAs(failure);
        verifyNoInteractions(dollarIndexSyncService, recordingService);
    }

    @Test
    @DisplayName("美元指数同步失败时不保存研究快照")
    void stopsWhenDollarIndexSyncFails() {
        when(goldPriceSyncService.syncDailyPrices()).thenReturn(goldResult());
        when(realRateSyncService.syncDailyObservations())
                .thenReturn(realRateResult());
        MacroDataUnavailableException failure =
                new MacroDataUnavailableException("美元指数暂时不可用");
        when(dollarIndexSyncService.syncDailyObservations())
                .thenThrow(failure);

        assertThatThrownBy(service::prepareDaily).isSameAs(failure);
        verifyNoInteractions(recordingService);
    }

    private GoldPriceSyncResult goldResult() {
        return new GoldPriceSyncResult(
                3, 2, 1, LocalDate.parse("2026-08-26")
        );
    }

    private RealRateSyncResult realRateResult() {
        return new RealRateSyncResult(5, 1, 3, 0, 1, collectedAt());
    }

    private DollarIndexSyncResult dollarIndexResult() {
        return new DollarIndexSyncResult(6, 1, 4, 0, 1, collectedAt());
    }

    private OffsetDateTime collectedAt() {
        return OffsetDateTime.parse("2026-08-27T01:00:00Z");
    }
}
