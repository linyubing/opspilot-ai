package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;
import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 验证真实Spring接线读取正式OHLC表；外部来源全部隔离，不制造真实行情。 */
@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "opspilot.forecast.gold.settlement-schedule.enabled=false",
        "opspilot.research.gold.daily-report-schedule.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GoldSettlementWiringTests {
    @Autowired GoldSettlementService settlement;
    @MockitoBean GoldDailyBarSyncService bars;
    @MockitoBean GoldPriceSyncService legacy;
    @MockitoBean GoldForecastResolutionService resolutions;

    @Test @DisplayName("实际每日结算先补齐正式OHLC日线，不再同步旧参考价表")
    void usesOhlc() {
        var sync = new GoldDailyBarSyncResult(2, 2, 0, LocalDate.parse("2026-08-31"));
        lenient().when(bars.syncRecent()).thenReturn(sync);
        lenient().when(legacy.syncDailyPrices()).thenReturn(new GoldPriceSyncResult(2, 2, 0, sync.latestPriceDate()));
        when(resolutions.resolvePending(100)).thenReturn(new ResolveGoldForecastsResult(1, 1, 0));
        var result = settlement.settleDaily();
        var order = inOrder(bars, resolutions);
        order.verify(bars).syncRecent();
        order.verify(resolutions).resolvePending(100);
        assertThat(result.priceSync()).isEqualTo(sync);
        verifyNoInteractions(legacy);
    }

    @Test @DisplayName("正式OHLC来源失败时不改用旧参考价，也不继续结算")
    void stopsOnOhlcFailure() {
        var error = new MarketDataUnavailableException("OHLC来源暂不可用");
        lenient().when(bars.syncRecent()).thenThrow(error);
        assertThatThrownBy(settlement::settleDaily).isSameAs(error);
        verifyNoInteractions(legacy, resolutions);
    }
}
