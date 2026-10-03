package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRecordingService;
import com.opspilot.ai.macrodata.DollarIndexSyncService;
import com.opspilot.ai.macrodata.RealRateSyncService;
import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;
import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 检查Spring真实装配选择OHLC同步，不用旧参考价冒充准备成功。 */
@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key",
        "opspilot.forecast.gold.settlement-schedule.enabled=false",
        "opspilot.research.gold.daily-report-schedule.enabled=false"})
class GoldPreparationChainTests {
    @Autowired private GoldResearchPreparationService service;
    @MockitoBean private GoldDailyBarSyncService bars;
    @MockitoBean private GoldPriceSyncService legacy;
    @MockitoBean private RealRateSyncService rates;
    @MockitoBean private DollarIndexSyncService dollars;
    @MockitoBean private GoldResearchSnapshotRecordingService recording;

    @Test
    @DisplayName("准备必须经过OHLC同步，旧参考价路径不能替代")
    void usesOhlc() {
        when(bars.sync()).thenReturn(new GoldDailyBarSyncResult(21, 21, 0,
                LocalDate.parse("2026-08-26")));
        var reached = new IllegalStateException("已到快照保存边界");
        when(recording.recordCurrentSnapshot()).thenThrow(reached);
        assertThatThrownBy(service::prepareDaily).isSameAs(reached);
        verifyNoInteractions(legacy);
    }

    @Test
    @DisplayName("OHLC失败必须停止，不能继续用旧库数据生成快照")
    void stopsOnOhlcFailure() {
        var failure = new MarketDataUnavailableException("OHLC暂不可用");
        when(bars.sync()).thenThrow(failure);
        assertThatThrownBy(service::prepareDaily).isSameAs(failure);
        verifyNoInteractions(legacy, rates, dollars, recording);
    }
}
