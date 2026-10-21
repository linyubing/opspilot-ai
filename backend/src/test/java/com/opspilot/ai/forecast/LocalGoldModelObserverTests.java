package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** HTTP数学夹具只检验准入与留痕，不是本地模型运行或黄金成绩。 */
class LocalGoldModelObserverTests {
    private static final String URL = "http://localhost:11435";
    private static final String MODEL = "qwen3.5:9b";
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final RestClient.Builder transport = RestClient.builder().baseUrl(URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(transport).build();
    private final LocalGoldModelObserver observer = new LocalGoldModelObserver(transport.build(), URI.create(URL), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void rejectsWrongOptionsBeforeSending() throws Exception {
        before();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.expect(org.springframework.test.web.client.ExpectedCount.between(0,1), requestTo(URL+"/api/chat"))
                .andExpect(request -> calls.incrementAndGet()).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        observer.customize(transport);
        var prompt = prompt();
        observer.start(MODEL,prompt);
        var body = json.createObjectNode().put("model",MODEL).put("think",true).put("stream",false);
        body.putObject("options").put("num_ctx",8192);
        body.putArray("messages").addObject().put("role","user").put("content",prompt.content());
        try {
            assertThatThrownBy(() -> transport.build().post().uri("/api/chat").body(body).retrieve().body(String.class))
                    .isInstanceOf(GoldForecastAiUnavailableException.class);
            assertThat(calls.get()).isZero();
            server.verify();
        } finally { observer.clear(); }
    }

    @Test void recordsActualSdkOptionsAndClearsBetweenCalls() throws Exception {
        for (int i=0;i<2;i++) {
            before(); chat(); after("a");
        }
        var gateway = gateway(true);
        var first = gateway.generate(prompt());
        var second = gateway.generate(prompt());
        assertThat(first.modelRun()).isNotNull();
        assertThat(first.modelRun().digest()).isEqualTo("a".repeat(64));
        assertThat(first.modelRun().parameters().path("think").booleanValue()).isFalse();
        assertThat(first.modelRun().parameters().path("options").path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(first.modelRun().parameters().has("messages")).isFalse();
        assertThat(first.modelRun().parameters().toString()).doesNotContain("不含行情的固定测试");
        assertThat(second.modelRun().key()).isEqualTo(first.modelRun().key());
        server.verify();
    }

    @Test void rejectsChangedDigestAfterCall() throws Exception {
        before(); chat();
        version(); tags("/api/tags", "b");
        assertThatThrownBy(() -> gateway(true).generate(prompt())).isInstanceOf(GoldForecastAiUnavailableException.class);
        server.verify();
    }

    @Test void rejectsMissingActualRequestCapture() throws Exception {
        before(); chat();
        assertThatThrownBy(() -> gateway(false).generate(prompt())).isInstanceOf(GoldForecastAiUnavailableException.class);
        server.verify();
    }

    @Test void hidesMetadataFailureAndDoesNotSendChat() {
        server.expect(requestTo(URL+"/api/version")).andRespond(withServerError());
        assertThatThrownBy(() -> gateway(true).generate(prompt())).isInstanceOf(GoldForecastAiUnavailableException.class)
                .hasMessageNotContaining(URL).hasCause(null);
        server.verify();
    }

    private SpringAiGoldForecastGateway gateway(boolean capture) {
        if (capture) observer.customize(transport);
        var api = OllamaApi.builder().baseUrl(URL).restClientBuilder(transport).build();
        var model = OllamaChatModel.builder().ollamaApi(api).defaultOptions(OllamaChatOptions.builder()
                .model(MODEL).numCtx(8192).numPredict(2048).temperature(0.0).build()).build();
        return new SpringAiGoldForecastGateway(ChatClient.builder(model).defaultOptions(
                OllamaChatOptions.builder().disableThinking().build()), json,
                new GoldForecastProperties(MODEL), Optional.of(observer));
    }
    private GoldForecastPrompt prompt() throws Exception {
        String content="不含行情的固定测试";
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION, content, hash);
    }
    private void before() { version(); tags("/api/tags", "a"); }
    private void after(String digest) { version(); tags("/api/tags",digest); tags("/api/ps",digest); }
    private void version() {
        server.expect(requestTo(URL+"/api/version")).andRespond(withSuccess("{\"version\":\"0.33.3\"}",MediaType.APPLICATION_JSON));
    }
    private void tags(String path, String digit) {
        server.expect(requestTo(URL+path)).andRespond(withSuccess("{\"models\":[{\"name\":\""+MODEL+"\",\"digest\":\""+digit.repeat(64)+"\"}]}",MediaType.APPLICATION_JSON));
    }
    private void chat() throws Exception {
        var body=json.createObjectNode().put("model",MODEL).put("done",true);
        body.putObject("message").put("role","assistant").put("content", """
                {"signal":"ABSTAINED","direction":null,"reasoning":"无行情输入","invalidationConditions":["真实研究另行执行"]}
                """);
        server.expect(requestTo(URL+"/api/chat")).andExpect(jsonPath("$.options.num_predict").value(2048))
                .andRespond(withSuccess(body.toString(),MediaType.APPLICATION_JSON));
    }
}
