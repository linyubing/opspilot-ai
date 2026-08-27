package com.opspilot.ai.macrodata;

import java.util.List;

/** 保存一次广义美元指数获取结果和缺失统计。 */
public record DollarIndexBatch(
        List<IncomingMacroObservation> observations,
        int receivedCount,
        int missingCount
) {
    public DollarIndexBatch {
        observations = List.copyOf(observations);
    }
}
