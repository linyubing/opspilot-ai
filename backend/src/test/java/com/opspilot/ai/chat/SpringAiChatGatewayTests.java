package com.opspilot.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class SpringAiChatGatewayTests {

    @Test
    void sendsMessageToModelAndReturnsGeneratedText(){
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();

        ChatModel model = prompt ->{
            capturedPrompt.set(prompt);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("模型回复"))
            ));
        };

        SpringAiChatGateway gateway =
                new SpringAiChatGateway(ChatClient.builder(model));

        String result = gateway.generate("你好");

        assertThat(result).isEqualTo("模型回复");
        assertThat(capturedPrompt.get().getContents()).contains("你好");
    }
}
