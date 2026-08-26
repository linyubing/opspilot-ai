package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.macrodata.RealRateSyncResult;

import java.time.OffsetDateTime;

public record RealRateSyncResponse(
        int receivedCount,
        int missingCount,
        int insertedCount,
        int revisedCount,
        int unchangedCount,
        OffsetDateTime collectedAt
) {

    public static RealRateSyncResponse from(RealRateSyncResult result) {
        return new RealRateSyncResponse(
                result.receivedCount(),
                result.missingCount(),
                result.insertedCount(),
                result.revisedCount(),
                result.unchangedCount(),
                result.collectedAt()
        );
    }
}
