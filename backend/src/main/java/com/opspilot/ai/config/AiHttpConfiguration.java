package com.opspilot.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** 为 Spring AI 使用的同步 HTTP 客户端设置有限等待时间。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiHttpProperties.class)
public class AiHttpConfiguration {

    @Bean
    RestClientCustomizer aiHttpCustomizer(AiHttpProperties properties) {
        return builder -> {
            SimpleClientHttpRequestFactory factory =
                    new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(properties.connectTimeout());
            factory.setReadTimeout(properties.readTimeout());
            builder.requestFactory(factory);
        };
    }
}
