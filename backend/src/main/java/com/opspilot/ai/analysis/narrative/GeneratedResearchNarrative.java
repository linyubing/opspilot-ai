package com.opspilot.ai.analysis.narrative;

/** 封装模型名称、原始响应和结构化研究解读。 */
public record GeneratedResearchNarrative(
        String modelName,
        String rawResponse,
        ResearchNarrativeContent content
) {
}
