package com.opspilot.ai.forecast;

/** 汇总一次待验证黄金预测解析任务的处理数量。 */
public record ResolveGoldForecastsResult(
        int scannedCount,
        int resolvedCount,
        int pendingCount
) {
}
