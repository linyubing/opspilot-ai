package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.ResolveGoldForecastsResult;

/** 返回一次预测解析任务的扫描、完成和等待数量。 */
public record ResolveGoldForecastsResponse(int scannedCount, int resolvedCount, int pendingCount) {
    public static ResolveGoldForecastsResponse from(ResolveGoldForecastsResult result) {
        return new ResolveGoldForecastsResponse(
                result.scannedCount(), result.resolvedCount(), result.pendingCount()
        );
    }
}
