package com.opspilot.ai.macrodata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FredRealRateProviderTests {

    private static final String TEST_API_KEY = "test-api-key";

    private HttpServer server;
    private String responseBody;
    private int responseStatus;
    private AtomicInteger requestCount;
    private volatile String lastRawQuery;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = "{}";
        responseStatus = 200;
        requestCount = new AtomicInteger();
        lastRawQuery = null;

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext(
                "/fred/series/observations",
                this::respond
        );
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("解析有效观测并把点号统计为缺失值")
    void parsesObservationsAndCountsMissingValues() {
        responseBody = """
                {
                  "realtime_start": "2026-08-21",
                  "realtime_end": "2026-08-21",
                  "observation_start": "1600-01-01",
                  "observation_end": "9999-12-31",
                  "units": "lin",
                  "output_type": 1,
                  "file_type": "json",
                  "order_by": "observation_date",
                  "sort_order": "asc",
                  "count": 3,
                  "offset": 0,
                  "limit": 100000,
                  "observations": [
                    {
                      "realtime_start": "2026-08-21",
                      "realtime_end": "2026-08-21",
                      "date": "2026-08-17",
                      "value": "1.850"
                    },
                    {
                      "realtime_start": "2026-08-21",
                      "realtime_end": "2026-08-21",
                      "date": "2026-08-18",
                      "value": "."
                    },
                    {
                      "realtime_start": "2026-08-21",
                      "realtime_end": "2026-08-21",
                      "date": "2026-08-19",
                      "value": "1.820"
                    }
                  ]
                }
                """;

        RealRateBatch batch = provider(TEST_API_KEY)
                .fetchDailyObservations();

        assertThat(batch.receivedCount()).isEqualTo(3);
        assertThat(batch.missingCount()).isEqualTo(1);
        assertThat(batch.observations())
                .extracting(IncomingMacroObservation::observationDate)
                .containsExactly(
                        LocalDate.parse("2026-08-17"),
                        LocalDate.parse("2026-08-19")
                );
        assertThat(batch.observations())
                .allSatisfy(observation -> {
                    assertThat(observation.seriesId()).isEqualTo("DFII10");
                    assertThat(observation.unit()).isEqualTo("percent");
                    assertThat(observation.provider()).isEqualTo("fred");
                });
        assertThat(lastRawQuery)
                .contains("series_id=DFII10")
                .contains("api_key=" + TEST_API_KEY)
                .contains("file_type=json");
    }

    @Test
    @DisplayName("响应缺少 observations 数组时拒绝整个批次")
    void rejectsResponseWithoutObservations() {
        responseBody = """
                {
                  "realtime_start": "2026-08-21",
                  "realtime_end": "2026-08-21"
                }
                """;

        assertThatThrownBy(() -> provider(TEST_API_KEY)
                .fetchDailyObservations())
                .isInstanceOf(MacroDataUnavailableException.class);
    }

    @Test
    @DisplayName("任意观测日期非法时拒绝整个批次")
    void rejectsInvalidObservationDate() {
        responseBody = responseWith("not-a-date", "1.850");

        assertThatThrownBy(() -> provider(TEST_API_KEY)
                .fetchDailyObservations())
                .isInstanceOf(MacroDataUnavailableException.class);
    }

    @Test
    @DisplayName("任意观测值非法时拒绝整个批次")
    void rejectsInvalidObservationValue() {
        responseBody = responseWith("2026-08-19", "not-a-number");

        assertThatThrownBy(() -> provider(TEST_API_KEY)
                .fetchDailyObservations())
                .isInstanceOf(MacroDataUnavailableException.class);
    }

    @Test
    @DisplayName("FRED 限流时返回安全异常且不泄露 Key")
    void hidesApiKeyWhenFredRejectsRequest() {
        responseStatus = 429;
        responseBody = """
                {
                  "error_code": 429,
                  "error_message": "rate limit for test-api-key"
                }
                """;

        assertThatThrownBy(() -> provider(TEST_API_KEY)
                .fetchDailyObservations())
                .isInstanceOf(MacroDataUnavailableException.class)
                .hasMessageNotContaining(TEST_API_KEY);
    }

    @Test
    @DisplayName("API Key 为空时在网络请求前失败")
    void rejectsBlankApiKeyBeforeRequest() {
        assertThatThrownBy(() -> provider(" ")
                .fetchDailyObservations())
                .isInstanceOf(MacroDataUnavailableException.class);

        assertThat(requestCount).hasValue(0);
    }

    private FredRealRateProvider provider(String apiKey) {
        URI baseUrl = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        FredProperties properties = new FredProperties(
                baseUrl,
                apiKey,
                "DFII10",
                "DTWEXBGS",
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );

        FredSeriesClient seriesClient = new FredSeriesClient(
                RestClient.builder().baseUrl(baseUrl.toString()).build(),
                properties
        );
        return new FredRealRateProvider(seriesClient, properties);
    }

    private String responseWith(String date, String value) {
        return """
                {
                  "realtime_start": "2026-08-21",
                  "realtime_end": "2026-08-21",
                  "observations": [
                    {
                      "realtime_start": "2026-08-21",
                      "realtime_end": "2026-08-21",
                      "date": "%s",
                      "value": "%s"
                    }
                  ]
                }
                """.formatted(date, value);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        lastRawQuery = exchange.getRequestURI().getRawQuery();
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8"
        );
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
