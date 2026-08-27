package com.opspilot.ai.analysis.narrative;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册黄金研究大模型解读所需的配置属性。 */
@Configuration
@EnableConfigurationProperties(ResearchNarrativeProperties.class)
public class ResearchNarrativeConfiguration {
}
