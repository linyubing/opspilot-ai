package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldPriceSyncResult;

/** 汇总一次黄金行情同步与预测结算的执行结果。 */
public record GoldSettlementResult(
        GoldPriceSyncResult priceSync,
        ResolveGoldForecastsResult forecastResolution
) {
}
