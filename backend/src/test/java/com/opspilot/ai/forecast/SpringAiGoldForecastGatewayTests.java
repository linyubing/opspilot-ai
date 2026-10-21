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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"other-model", ""})
    @DisplayName("正式预测缺少响应模型或与请求不一致时不能冒用配置名")
    void rejectsUnverifiedModel(String reported) {
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage(VALID_JSON))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model(reported).build());
        var formal = new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION, "身份核验数学样例", "a".repeat(64));
        assertThatThrownBy(() -> gateway(p -> response).generate(formal))
                .isInstanceOf(InvalidGoldForecastAiResponseException.class);
    }

    @Test @DisplayName("从响应元数据保留模型标识，不从正文或配置补造")
    void preservesReportedModel() {
        AtomicReference<Prompt> sent = new AtomicReference<>();
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage(VALID_JSON))),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model("glm-4.7").build());
        var result = gateway(p -> { sent.set(p); return response; }).generate(
                new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION, "响应身份样例", "a".repeat(64)));
        assertThat(sent.get().getContents()).isEqualTo("响应身份样例");
        assertThat(result.reportedModel()).isEqualTo("glm-4.7");
        assertThat(result.content().direction()).isEqualTo(ForecastDirection.NEUTRAL);
    }

    @Test @DisplayName("真实Ollama SDK请求保留关闭思考及本地模型的上下文输出温度参数")
    void preservesOllamaOptions() throws Exception {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var api = org.springframework.ai.ollama.api.OllamaApi.builder().baseUrl("http://localhost:11435")
                .restClientBuilder(builder).build();
        var model = org.springframework.ai.ollama.OllamaChatModel.builder().ollamaApi(api)
                .defaultOptions(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                        .model("qwen3.5:9b").numCtx(8192).numPredict(2048).temperature(0.0).build()).build();
        var clientBuilder = ChatClient.builder(model).defaultOptions(
                org.springframework.ai.ollama.api.OllamaChatOptions.builder().disableThinking().build());
        var json = new ObjectMapper().createObjectNode().put("model", "qwen3.5:9b").put("done", true);
        json.putObject("message").put("role", "assistant").put("content", VALID_JSON);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://localhost:11435/api/chat"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.model").value("qwen3.5:9b"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.think").value(false))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.options.num_ctx").value(8192))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.options.num_predict").value(2048))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.options.temperature").value(0.0))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        json.toString(), org.springframework.http.MediaType.APPLICATION_JSON));
        var gateway = new SpringAiGoldForecastGateway(clientBuilder, new ObjectMapper(), new GoldForecastProperties("qwen3.5:9b"));
        var result = gateway.generate(new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION, "SDK参数测试，不含行情", "a".repeat(64)));
        assertThat(result.reportedModel()).isEqualTo("qwen3.5:9b");
        server.verify();
    }

    @Test
    @DisplayName("正式弃权JSON保留null方向，不强转为中性")
    void parsesAbstention() {
        var result = gateway(p -> response("""
                {"signal":"ABSTAINED","direction":null,"reasoning":"证据不足", "invalidationConditions":["取得更多真实证据"]}
                """)).generate(prompt());
        assertThat(result.content().signal()).isEqualTo(ForecastSignal.ABSTAINED);
        assertThat(result.content().direction()).isNull();
        new GoldForecastValidator().validateFormal(result.content());
    }

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
