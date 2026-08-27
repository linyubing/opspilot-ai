package com.opspilot.ai.analysis.narrative;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 保存与正式快照绑定、可审计且不可变的黄金研究解读记录。 */
public record StoredResearchNarrative(
        UUID id,
        UUID snapshotId,
        ResearchNarrativeContent content,
        String modelName,
        String promptVersion,
        String promptHash,
        String rawResponse,
        OffsetDateTime createdAt
) {
}
