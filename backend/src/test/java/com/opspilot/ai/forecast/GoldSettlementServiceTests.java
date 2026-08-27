package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证每日结算严格按照“先同步行情、后结算预测”的顺序执行。 */
@ExtendWith(MockitoExtension.class)
class GoldSettlementServiceTests {

    @Mock
    private GoldPriceSyncService priceSyncService;

    @Mock
    private GoldForecastResolutionService resolutionService;

    @Test
    @DisplayName("先同步真实行情，再结算待验证预测")
    void syncsPricesBeforeResolvingPendingForecasts() {
        GoldPriceSyncResult priceSync = new GoldPriceSyncResult(
                3, 2, 1, LocalDate.parse("2026-08-27")
        );
        ResolveGoldForecastsResult resolution =
                new ResolveGoldForecastsResult(2, 1, 1);
        when(priceSyncService.syncDailyPrices()).thenReturn(priceSync);
        when(resolutionService.resolvePending(100)).thenReturn(resolution);
        GoldSettlementService service = new GoldSettlementService(
                priceSyncService,
                resolutionService
        );

        GoldSettlementResult result = service.settleDaily();

        assertThat(result.priceSync()).isSameAs(priceSync);
        assertThat(result.forecastResolution()).isSameAs(resolution);
        InOrder order = inOrder(priceSyncService, resolutionService);
        order.verify(priceSyncService).syncDailyPrices();
        order.verify(resolutionService).resolvePending(100);
    }

    @Test
    @DisplayName("行情同步失败时不执行预测结算")
    void doesNotResolveForecastsWhenPriceSyncFails() {
        MarketDataUnavailableException failure =
                new MarketDataUnavailableException("黄金行情暂时不可用");
        when(priceSyncService.syncDailyPrices()).thenThrow(failure);
        GoldSettlementService service = new GoldSettlementService(
                priceSyncService,
                resolutionService
        );

        assertThatThrownBy(service::settleDaily).isSameAs(failure);
        verifyNoInteractions(resolutionService);
    }
}
