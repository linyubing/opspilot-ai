package com.opspilot.ai.macrodata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FredSeriesClientTests {

    private static final String TEST_KEY = "fred-client-test-key";

    private HttpServer server;
    private String responseBody;
    private AtomicInteger requestCount;
    private volatile String rawQuery;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = "{}";
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fred/series/observations", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("解析有效观测并统计 FRED 点号缺失值")
    void parsesObservationsAndCountsMissingValues() {
        responseBody = """
                {"observations":[
                  {"date":"2026-08-20","value":"119.1234"},
                  {"date":"2026-08-21","value":"."}
                ]}
                """;

        FredSeriesBatch batch = client(TEST_KEY).fetch("DTWEXBGS");

        assertThat(batch.receivedCount()).isEqualTo(2);
        assertThat(batch.missingCount()).isEqualTo(1);
        assertThat(batch.observations()).containsExactly(
                new FredSeriesObservation(
                        LocalDate.parse("2026-08-20"),
                        new BigDecimal("119.1234")
                )
        );
        assertThat(rawQuery)
                .contains("series_id=DTWEXBGS")
                .contains("file_type=json");
    }

    @Test
    @DisplayName("空 API Key 在网络请求前失败")
    void rejectsBlankApiKeyBeforeRequest() {
        assertThatThrownBy(() -> client(" ").fetch("DTWEXBGS"))
                .isInstanceOf(MacroDataUnavailableException.class);
        assertThat(requestCount).hasValue(0);
    }

    private FredSeriesClient client(String apiKey) {
        URI baseUrl = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        FredProperties properties = new FredProperties(
                baseUrl,
                apiKey,
                "DFII10",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        return new FredSeriesClient(
                RestClient.builder().baseUrl(baseUrl.toString()).build(),
                properties
        );
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        rawQuery = exchange.getRequestURI().getRawQuery();
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
