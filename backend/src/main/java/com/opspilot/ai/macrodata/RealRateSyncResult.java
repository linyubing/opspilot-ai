package com.opspilot.ai.macrodata;

import java.time.OffsetDateTime;

/**
 * 一次实际利率同步的分类统计。
 */
public record RealRateSyncResult(
        int receivedCount,
        int missingCount,
        int insertedCount,
        int revisedCount,
        int unchangedCount,
        OffsetDateTime collectedAt
) {
}
