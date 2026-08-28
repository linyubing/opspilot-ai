package com.opspilot.ai.analysis.narrative;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 定义黄金研究解读的幂等保存和历史查询边界。 */
public interface ResearchNarrativeRepository {

    Optional<StoredResearchNarrative> findByKey(
            UUID snapshotId,
            String modelName,
            String promptVersion
    );

    SaveResearchNarrativeResult saveIfAbsent(
            StoredResearchNarrative candidate
    );

    List<StoredResearchNarrative> findBySnapshotId(UUID snapshotId);

    Optional<StoredResearchNarrative> findLatestBySnapshotId(UUID snapshotId);
}
