package com.opspilot.ai.analysis.narrative;

import java.util.List;

/** 保存大模型生成并等待安全校验的结构化黄金研究解读。 */
public record ResearchNarrativeContent(
        String summary,
        String realRateAnalysis,
        String dollarIndexAnalysis,
        List<String> risks,
        List<String> watchList,
        String disclaimer
) {
}
