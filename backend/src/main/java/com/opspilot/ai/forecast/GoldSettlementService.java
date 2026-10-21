package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;
import org.springframework.stereotype.Service;

/** 编排真实黄金行情同步和待验证预测结算，不生成新的大模型预测。 */
@Service
public class GoldSettlementService {

    private static final int RESOLUTION_LIMIT = 100;

    private final GoldDailyBarSyncService priceSyncService;
    private final GoldForecastResolutionService resolutionService;

    public GoldSettlementService(
            GoldDailyBarSyncService priceSyncService,
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
        // 与结算仓储使用同一份Twelve Data OHLC输入；最多补5个近期缺日，不扫全历史。
        GoldDailyBarSyncResult priceSync = priceSyncService.syncRecent();
        ResolveGoldForecastsResult forecastResolution =
                resolutionService.resolvePending(RESOLUTION_LIMIT);

        return new GoldSettlementResult(priceSync, forecastResolution);
    }
}
