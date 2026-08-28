package com.opspilot.ai.forecast.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.GoldForecastProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证复盘网关的模型调用、JSON 解析和异常边界。 */
class SpringAiGoldForecastReviewGatewayTests {

    private static final String VALID_JSON = """
            {
              "summary":"整体表现优于基线",
              "directionBiases":[],
              "recentPerformance":"近期稳定",
              "versionFindings":[],
              "improvementHypotheses":[],
              "risks":["样本有限"],
              "disclaimer":"不构成投资建议"
            }
            """;

    @Test
    @DisplayName("调用模型并解析结构化复盘")
    void callsModelAndParsesJson() {
        GeneratedGoldForecastReview result = gateway(
                prompt -> response(VALID_JSON)
        ).generate(prompt());

        assertThat(result.modelName()).isEqualTo("glm-4.7");
        assertThat(result.rawResponse()).isEqualTo(VALID_JSON);
        assertThat(result.content().summary())
                .isEqualTo("整体表现优于基线");
    }

    @Test
    @DisplayName("区分非法 JSON 和模型上游故障")
    void separatesInvalidJsonAndUpstreamFailure() {
        assertThatThrownBy(() -> gateway(
                prompt -> response("不是 JSON")
        ).generate(prompt()))
                .isInstanceOf(
                        InvalidGoldForecastReviewAiResponseException.class
                );

        assertThatThrownBy(() -> gateway(prompt -> {
            throw new IllegalStateException("上游失败");
        }).generate(prompt()))
                .isInstanceOf(
                        GoldForecastReviewAiUnavailableException.class
                )
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private SpringAiGoldForecastReviewGateway gateway(ChatModel model) {
        return new SpringAiGoldForecastReviewGateway(
                ChatClient.builder(model),
                new ObjectMapper(),
                new GoldForecastProperties("glm-4.7")
        );
    }

    private GoldForecastReviewPrompt prompt() {
        return new GoldForecastReviewPrompt(
                GoldForecastReviewPromptBuilder.PROMPT_VERSION,
                "真实历史评测提示词"
        );
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))
        ));
    }
}
