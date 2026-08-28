package com.opspilot.ai.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 AI 使用的同步 HTTP 客户端会在读取超时后释放调用线程。 */
class AiHttpConfigurationTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stopsWaitingAfterReadTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1000);
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        AiHttpProperties properties = new AiHttpProperties(
                Duration.ofMillis(100),
                Duration.ofMillis(100)
        );
        RestClientCustomizer customizer =
                new AiHttpConfiguration().aiHttpCustomizer(properties);
        RestClient.Builder builder = RestClient.builder();
        customizer.customize(builder);
        RestClient client = builder.baseUrl(
                "http://localhost:" + server.getAddress().getPort()
        ).build();

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.get()
                .uri("/slow")
                .retrieve()
                .body(String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(Duration.ofMillis(800));
    }
}
