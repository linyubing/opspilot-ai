package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import org.springframework.stereotype.Service;

/** 编排真实黄金行情同步和待验证预测结算，不生成新的大模型预测。 */
@Service
public class GoldSettlementService {

    private static final int RESOLUTION_LIMIT = 100;

    private final GoldPriceSyncService priceSyncService;
    private final GoldForecastResolutionService resolutionService;

    public GoldSettlementService(
            GoldPriceSyncService priceSyncService,
            GoldForecastResolutionService resolutionService
    ) {
        this.priceSyncService = priceSyncService;
        this.resolutionService = resolutionService;
    }

    public GoldSettlementResult settleDaily() {
        /*
         * 必须先同步真实行情，再尝试结算；同步失败时直接终止，
         * 避免用数据库中的旧行情制造一次看似成功的结算。
         */
        GoldPriceSyncResult priceSync = priceSyncService.syncDailyPrices();
        ResolveGoldForecastsResult forecastResolution =
                resolutionService.resolvePending(RESOLUTION_LIMIT);

        return new GoldSettlementResult(priceSync, forecastResolution);
    }
}
