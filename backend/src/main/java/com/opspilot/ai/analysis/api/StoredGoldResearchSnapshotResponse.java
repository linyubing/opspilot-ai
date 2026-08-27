package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 表示可通过 API 查询的正式黄金研究历史记录。
 */
public record StoredGoldResearchSnapshotResponse(
        UUID id,
        GoldResearchSnapshotResponse snapshot,
        OffsetDateTime createdAt
) {
    public static StoredGoldResearchSnapshotResponse from(
            StoredGoldResearchSnapshot record
    ) {
        return new StoredGoldResearchSnapshotResponse(
                record.id(),
                GoldResearchSnapshotResponse.from(record.snapshot()),
                record.createdAt()
        );
    }
}
