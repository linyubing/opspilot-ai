package com.opspilot.ai.analysis.narrative.api;

import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;

/** 返回正式解读记录以及本次请求是否创建了新记录。 */
public record SaveResearchNarrativeResponse(
        ResearchNarrativeResponse record,
        boolean created
) {

    public static SaveResearchNarrativeResponse from(
            SaveResearchNarrativeResult result
    ) {
        return new SaveResearchNarrativeResponse(
                ResearchNarrativeResponse.from(result.record()),
                result.created()
        );
    }
}
