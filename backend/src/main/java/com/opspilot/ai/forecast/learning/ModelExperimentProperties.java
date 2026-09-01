package com.opspilot.ai.forecast.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型实验配置属性。 */
@ConfigurationProperties(prefix = "opspilot.build")
public record ModelExperimentProperties(String gitCommit) {
    public ModelExperimentProperties {
        if (gitCommit == null || gitCommit.isBlank()) {
            gitCommit = "unknown";
        }
    }

    public String gitCommit() {
        return gitCommit;
    }
}
