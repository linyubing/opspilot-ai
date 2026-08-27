package com.opspilot.ai.macrodata;

import java.util.List;

/** 表示一次通用 FRED 序列响应的有效、接收与缺失统计。 */
public record FredSeriesBatch(
        List<FredSeriesObservation> observations,
        int receivedCount,
        int missingCount
) {
    public FredSeriesBatch {
        observations = List.copyOf(observations);
    }
}
