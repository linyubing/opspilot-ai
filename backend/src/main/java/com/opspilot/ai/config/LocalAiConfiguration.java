package com.opspilot.ai.config;

import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 本地聊天关闭额外思考输出，避免输出额度耗尽后没有可解析的正文。 */
@Profile("local-ai")
@Configuration(proxyBeanMethods = false)
public class LocalAiConfiguration {

    @Bean
    ChatClientCustomizer localChatOptions() {
        // 只覆盖思考开关；模型、上下文和温度仍由 local-ai 配置提供。
        return builder -> builder.defaultOptions(
                OllamaChatOptions.builder().disableThinking().build()
        );
    }
}
