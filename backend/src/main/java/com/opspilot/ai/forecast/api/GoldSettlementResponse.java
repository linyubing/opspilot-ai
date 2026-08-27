package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.GoldSettlementResult;
import com.opspilot.ai.marketdata.api.GoldPriceSyncResponse;

/** 返回黄金行情同步和预测结算两个阶段的公开结果。 */
public record GoldSettlementResponse(
        GoldPriceSyncResponse priceSync,
        ResolveGoldForecastsResponse forecastResolution
) {

    public static GoldSettlementResponse from(GoldSettlementResult result) {
        return new GoldSettlementResponse(
                GoldPriceSyncResponse.from(result.priceSync()),
                ResolveGoldForecastsResponse.from(result.forecastResolution())
        );
    }
}
