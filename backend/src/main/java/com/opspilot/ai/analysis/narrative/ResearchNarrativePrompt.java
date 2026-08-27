package com.opspilot.ai.analysis.narrative;

/** 保存不可变的提示词版本、完整内容和 SHA-256 摘要。 */
public record ResearchNarrativePrompt(
        String version,
        String content,
        String sha256
) {
}
