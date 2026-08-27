package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.macrodata.DollarIndexSyncResult;

import java.time.OffsetDateTime;

/** 对外返回一次广义美元指数同步统计。 */
public record DollarIndexSyncResponse(
        int receivedCount,
        int missingCount,
        int insertedCount,
        int revisedCount,
        int unchangedCount,
        OffsetDateTime collectedAt
) {
    public static DollarIndexSyncResponse from(DollarIndexSyncResult result) {
        return new DollarIndexSyncResponse(
                result.receivedCount(),
                result.missingCount(),
                result.insertedCount(),
                result.revisedCount(),
                result.unchangedCount(),
                result.collectedAt()
        );
    }
}
