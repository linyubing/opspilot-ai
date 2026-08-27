package com.opspilot.ai.analysis.narrative;

/** 定义黄金研究专用大模型调用边界。 */
@FunctionalInterface
public interface ResearchNarrativeGateway {

    GeneratedResearchNarrative generate(ResearchNarrativePrompt prompt);
}
