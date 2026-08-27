package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 验证 Spring AI 黄金方向预测网关的调用、解析、异常和日志边界。 */
@ExtendWith(OutputCaptureExtension.class)
class SpringAiGoldForecastGatewayTests {

    private static final String VALID_JSON = """
            {"direction":"NEUTRAL","reasoning":"双因子信号不一致。","invalidationConditions":["实际利率明显上升"]}
            """;

    @Test
    @DisplayName("发送完整提示词并解析结构化预测")
    void sendsPromptAndParsesResponse() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        SpringAiGoldForecastGateway gateway = gateway(prompt -> {
            captured.set(prompt);
            return response(VALID_JSON);
        });

        GeneratedGoldForecast result = gateway.generate(prompt());

        assertThat(captured.get().getContents()).contains("完整预测提示词-4520.00894962");
        assertThat(result.modelName()).isEqualTo("glm-4.7");
        assertThat(result.rawResponse()).isEqualTo(VALID_JSON);
        assertThat(result.content().direction()).isEqualTo(ForecastDirection.NEUTRAL);
    }

    @Test
    @DisplayName("区分非法 JSON 与模型上游故障")
    void separatesInvalidJsonFromUpstreamFailure() {
        assertThatThrownBy(() -> gateway(p -> response("不是 JSON")).generate(prompt()))
                .isInstanceOf(InvalidGoldForecastAiResponseException.class);

        assertThatThrownBy(() -> gateway(p -> {
            throw new IllegalStateException("敏感上游错误");
        }).generate(prompt()))
                .isInstanceOf(GoldForecastAiUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("日志只记录调用元数据而不泄露提示词和响应")
    void logsMetadataWithoutSensitiveContent(CapturedOutput output) {
        gateway(p -> response(VALID_JSON)).generate(prompt());

        assertThat(output.getOut())
                .contains("黄金方向预测模型调用完成", "模型=glm-4.7", "提示词长度=", "响应长度=", "耗时=")
                .doesNotContain("4520.00894962", "双因子信号不一致");
    }

    private SpringAiGoldForecastGateway gateway(ChatModel model) {
        return new SpringAiGoldForecastGateway(
                ChatClient.builder(model), new ObjectMapper(),
                new GoldForecastProperties("glm-4.7")
        );
    }

    private GoldForecastPrompt prompt() {
        return new GoldForecastPrompt(
                "gold-direction-forecast-prompt-v1",
                "完整预测提示词-4520.00894962",
                "a".repeat(64)
        );
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
