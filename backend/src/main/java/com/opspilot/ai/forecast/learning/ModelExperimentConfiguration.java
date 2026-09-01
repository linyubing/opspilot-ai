package com.opspilot.ai.forecast.learning;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册黄金模型实验相关配置属性。 */
@Configuration
@EnableConfigurationProperties({
        ModelExperimentProperties.class,
        XgboostProperties.class
})
public class ModelExperimentConfiguration {
}
