package com.opspilot.ai.macrodata;

import java.time.OffsetDateTime;

/** 保存一次广义美元指数同步的分类统计。 */
public record DollarIndexSyncResult(
        int receivedCount,
        int missingCount,
        int insertedCount,
        int revisedCount,
        int unchangedCount,
        OffsetDateTime collectedAt
) {
}
