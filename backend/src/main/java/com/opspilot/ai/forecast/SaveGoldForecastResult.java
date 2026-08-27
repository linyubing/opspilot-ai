package com.opspilot.ai.forecast;

/** 返回数据库最终保留的预测以及本次是否实际创建。 */
public record SaveGoldForecastResult(StoredGoldDirectionForecast record, boolean created) {
}
