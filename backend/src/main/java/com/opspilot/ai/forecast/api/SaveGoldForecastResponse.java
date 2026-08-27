package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.SaveGoldForecastResult;

/** 返回预测记录以及本次请求是否真正创建了新记录。 */
public record SaveGoldForecastResponse(boolean created, GoldForecastResponse record) {
    public static SaveGoldForecastResponse from(SaveGoldForecastResult result) {
        return new SaveGoldForecastResponse(result.created(), GoldForecastResponse.from(result.record()));
    }
}
