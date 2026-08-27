package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;

/**
 * 表示正式快照写入结果，created=false 代表命中已有记录。
 */
public record SaveGoldResearchSnapshotResponse(
        boolean created,
        StoredGoldResearchSnapshotResponse record
) {
    public static SaveGoldResearchSnapshotResponse from(
            SaveGoldResearchSnapshotResult result
    ) {
        return new SaveGoldResearchSnapshotResponse(
                result.created(),
                StoredGoldResearchSnapshotResponse.from(result.record())
        );
    }
}
