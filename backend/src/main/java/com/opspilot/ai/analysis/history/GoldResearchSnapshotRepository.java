package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.GoldResearchSnapshot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定义黄金研究快照的不可变保存与最近历史查询契约。
 */
public interface GoldResearchSnapshotRepository {

    SaveGoldResearchSnapshotResult saveIfAbsent(
            GoldResearchSnapshot snapshot,
            OffsetDateTime createdAt
    );

    List<StoredGoldResearchSnapshot> findRecent(int limit);

    Optional<StoredGoldResearchSnapshot> findById(UUID id);
}
