package com.opspilot.ai.analysis.narrative;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 保存黄金研究大模型解读的专用配置。 */
@ConfigurationProperties("opspilot.research.narrative")
public record ResearchNarrativeProperties(String modelName) {
}
