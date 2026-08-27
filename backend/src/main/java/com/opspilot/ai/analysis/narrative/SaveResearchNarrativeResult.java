package com.opspilot.ai.analysis.narrative;

/** 返回数据库最终保留的解读记录以及本次是否实际创建。 */
public record SaveResearchNarrativeResult(
        StoredResearchNarrative record,
        boolean created
) {
}
