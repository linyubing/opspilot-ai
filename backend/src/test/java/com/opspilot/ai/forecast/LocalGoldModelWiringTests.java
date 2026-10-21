package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 真实Spring自动装配与HTTP夹具连通验证；不调用真实模型或写入预测。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
@ActiveProfiles("local-ai")
class LocalGoldModelWiringTests {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer SERVER = server();
    @Autowired private SpringAiGoldForecastGateway gateway;
    @Autowired private GoldForecastProperties properties;

    @DynamicPropertySource static void localEndpoint(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @Test void autoConfiguredClientCapturesRunWithoutManualCustomizer() throws Exception {
        assertThat(properties.requireRun()).isTrue();
        String content="Spring自动装配身份测试，不含行情";
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        var result=gateway.generate(new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION,content,hash));
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(result.modelRun()).isNotNull();
        assertThat(result.modelRun().promptHash()).isEqualTo(hash);
        assertThat(result.modelRun().parameters().path("think").booleanValue()).isFalse();
        assertThat(result.modelRun().parameters().path("options").path("num_ctx").asInt()).isEqualTo(8192);
    }

    @AfterAll static void closeServer() { SERVER.stop(0); }

    private static HttpServer server() {
        try {
            var json=new ObjectMapper();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/", exchange -> {
                String path=exchange.getRequestURI().getPath();
                String response;
                if (path.equals("/api/chat")) {
                    CALLS.incrementAndGet();
                    var body=json.createObjectNode().put("model","qwen3.5:9b").put("done",true);
                    body.putObject("message").put("role","assistant").put("content", """
                            {"signal":"ABSTAINED","direction":null,"reasoning":"无行情身份夹具","invalidationConditions":["真实研究另行执行"]}
                            """);
                    response=body.toString();
                } else if (path.equals("/api/version")) response="{\"version\":\"0.33.3\"}";
                else response="{\"models\":[{\"name\":\"qwen3.5:9b\",\"digest\":\""+"a".repeat(64)+"\"}]}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
