package com.opspilot.ai.macrodata;

import java.util.List;

/**
 * FRED 一次响应的解析结果。
 */
public record RealRateBatch(
        List<IncomingMacroObservation> observations,
        int receivedCount,
        int missingCount
) {

    public RealRateBatch {
        observations = List.copyOf(observations);
    }
}
