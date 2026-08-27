package com.opspilot.ai.analysis.narrative;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class SpringAiResearchNarrativeGatewayTests {

    private static final String VALID_JSON = """
            {
              "summary": "双因子当前一中性一支持。",
              "realRateAnalysis": "实际利率因子为中性。",
              "dollarIndexAnalysis": "美元指数因子为支持。",
              "risks": ["数据日期可能不一致。"],
              "watchList": ["观察两个因子的后续变化。"],
              "disclaimer": "不构成价格预测、交易或投资建议。"
            }
            """;

    @Test
    void sendsCompletePromptAndParsesStructuredResponse() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        SpringAiResearchNarrativeGateway gateway = gateway(prompt -> {
            captured.set(prompt);
            return response(VALID_JSON);
        });

        GeneratedResearchNarrative result = gateway.generate(prompt());

        assertThat(captured.get().getContents()).contains("完整研究提示词-4520.00894962");
        assertThat(result.modelName()).isEqualTo("glm-4.7");
        assertThat(result.rawResponse()).isEqualTo(VALID_JSON);
        assertThat(result.content().summary()).isEqualTo("双因子当前一中性一支持。");
        assertThat(result.content().risks()).containsExactly("数据日期可能不一致。");
    }

    @Test
    void convertsInvalidJsonToDedicatedException() {
        SpringAiResearchNarrativeGateway gateway = gateway(
                prompt -> response("不是 JSON")
        );

        assertThatThrownBy(() -> gateway.generate(prompt()))
                .isInstanceOf(InvalidResearchAiResponseException.class)
                .hasMessage("大模型未返回合法的结构化研究解读");
    }

    @Test
    void convertsModelFailureToUnavailableException() {
        SpringAiResearchNarrativeGateway gateway = gateway(prompt -> {
            throw new IllegalStateException("上游敏感错误");
        });

        assertThatThrownBy(() -> gateway.generate(prompt()))
                .isInstanceOf(ResearchAiUnavailableException.class)
                .hasMessage("黄金研究大模型暂时不可用，请稍后重试")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void logsOnlyMetadata(CapturedOutput output) {
        SpringAiResearchNarrativeGateway gateway = gateway(
                prompt -> response(VALID_JSON)
        );

        gateway.generate(prompt());

        assertThat(output.getOut())
                .contains("黄金研究大模型解读完成")
                .contains("模型=glm-4.7")
                .contains("提示词长度=")
                .contains("响应长度=")
                .contains("耗时=")
                .doesNotContain("4520.00894962")
                .doesNotContain("双因子当前一中性一支持");
    }

    private SpringAiResearchNarrativeGateway gateway(ChatModel model) {
        return new SpringAiResearchNarrativeGateway(
                ChatClient.builder(model),
                new ObjectMapper(),
                new ResearchNarrativeProperties("glm-4.7")
        );
    }

    private ResearchNarrativePrompt prompt() {
        return new ResearchNarrativePrompt(
                "gold-narrative-prompt-v1",
                "完整研究提示词-4520.00894962",
                "a".repeat(64)
        );
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))
        ));
    }
}
