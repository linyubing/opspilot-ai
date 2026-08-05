package com.opspilot.ai.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class SpringAiChatGateway implements ChatGateway {

    private final ChatClient chatClient;

    public SpringAiChatGateway(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String generate(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
