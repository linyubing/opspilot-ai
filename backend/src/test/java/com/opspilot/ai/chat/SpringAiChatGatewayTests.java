package com.opspilot.ai.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
public class SpringAiChatGatewayTests {

    @Test
    void sendsMessageToModelAndReturnsGeneratedText() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();

        ChatModel model = prompt -> {
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

    @Test
    void logsCallMetadataWithoutSensitiveContent(CapturedOutput output) {
        ChatModel model = prompt -> new ChatResponse(List.of(
                new Generation(new AssistantMessage("模型回复"))
        ));

        SpringAiChatGateway gateway =
                new SpringAiChatGateway(ChatClient.builder(model));

        gateway.generate("你好");

        assertThat(output.getOut())
                .contains("AI 对话调用完成")
                .contains("问题长度=2")
                .contains("回答长度=4")
                .contains("耗时=")
                .doesNotContain("你好")
                .doesNotContain("模型回复");
    }

    @Test
    void convertsModelFailureToSafeUpstreamException(){
        ChatModel model = prompt -> {
            throw new IllegalStateException("上游返回的原始敏感错误");
        };

        SpringAiChatGateway gateway =
                new SpringAiChatGateway(ChatClient.builder(model));
        assertThatThrownBy(()-> gateway.generate("敏感问题"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("AI 服务暂时不可用，请稍后重试")
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(error->
                        assertThat(error.getClass().getSimpleName())
                                .isEqualTo("UpstreamAiException")
                );

    }
}