package com.opspilot.ai.analysis.narrative.api;

import com.opspilot.ai.analysis.narrative.ResearchNarrativeContent;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 对外返回结构化研究解读和必要审计信息，不暴露原始模型响应。 */
public record ResearchNarrativeResponse(
        UUID id,
        UUID snapshotId,
        ResearchNarrativeContent content,
        String modelName,
        String promptVersion,
        String promptHash,
        OffsetDateTime createdAt
) {

    public static ResearchNarrativeResponse from(
            StoredResearchNarrative record
    ) {
        return new ResearchNarrativeResponse(
                record.id(),
                record.snapshotId(),
                record.content(),
                record.modelName(),
                record.promptVersion(),
                record.promptHash(),
                record.createdAt()
        );
    }
}
