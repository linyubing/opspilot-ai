package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import com.opspilot.ai.marketdata.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 用真实 HTTP 客户端和同步服务验证最新日线隔离；数学样例不进入真实数据库。 */
class GoldLatestSyncTests {
    private HttpServer server;
    private MockMvc mvc;
    private GoldDailyBarRepository repository;
    private String query;
    private int dailyCalls;
    private String row = """
            {"datetime":"2026-08-28","open":"10","high":"12","low":"9","close":"11"}
            """;
    private String quote;
    private String selected;

    @BeforeEach
    void setup() throws IOException {
        quote = row.replace("{", "{\"symbol\":\"XAU/USD\",");
        selected = row;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quote", exchange -> send(exchange, quote));
        server.createContext("/time_series", exchange -> {
            query = exchange.getRequestURI().getRawQuery();
            dailyCalls++;
            // 全量历史有旧日冲突；按明确日期请求才返回独立窗口。
            String rows = query.contains("date=2026-") ? selected : row + "," + row;
            send(exchange, "{\"meta\":{\"symbol\":\"XAU/USD\",\"interval\":\"1day\"},"
                    + "\"status\":\"ok\",\"values\":[" + rows + "]}");
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T01:00:00Z"), ZoneOffset.UTC);
        var provider = new TwelveDataGoldBarProvider(RestClient.builder().baseUrl(base.toString()).build(),
                new TwelveDataProperties(base, "test-key", Duration.ofSeconds(2), Duration.ofSeconds(2)), clock);
        repository = mock(GoldDailyBarRepository.class);
        mvc = standaloneSetup(new GoldDailyBarController(
                new GoldDailyBarSyncService(provider, repository), repository, clock))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void savesOnlyConfirmedDay() throws Exception {
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(1))
                .andExpect(jsonPath("$.latestPriceDate").value("2026-08-28"));
        assertThat(query).contains("date=2026-08-28", "interval=1day")
                .doesNotContain("start_date=", "end_date=");
        assertThat(dailyCalls).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GoldDailyBar>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        GoldDailyBar bar = saved.getValue().getFirst();
        assertThat(bar.close()).isEqualByComparingTo("11");
        assertThat(bar.isConfirmedAt(OffsetDateTime.parse("2026-08-31T01:00:00Z"))).isTrue();
    }

    @Test
    void fullHistoryStillRejectsDuplicates() throws Exception {
        mvc.perform(post("/api/market-data/gold/daily-bars/sync"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsDuplicateSelectedDay() throws Exception {
        selected = row + "," + row.replace("\"11\"", "\"10\"");
        failsWithoutSaving();
    }

    @Test
    void rejectsExtraDay() throws Exception {
        selected = row + "," + row.replace("2026-08-28", "2026-08-27");
        failsWithoutSaving();
    }

    @Test
    void rejectsQuoteMismatch() throws Exception {
        quote = quote.replace("\"11\"", "\"10\"");
        failsWithoutSaving();
    }

    @Test
    void rejectsInvalidQuoteBeforeDailyRequest() throws Exception {
        quote = quote.replace("XAU/USD", "EUR/USD");
        failsWithoutSaving();
        assertThat(dailyCalls).isZero();
    }

    @Test
    void rejectsFutureQuoteBeforeDailyRequest() throws Exception {
        quote = quote.replace("2026-08-28", "2099-01-01");
        failsWithoutSaving();
        assertThat(dailyCalls).isZero();
    }

    @Test
    void doesNotSaveWeekendDay() throws Exception {
        quote = quote.replace("2026-08-28", "2026-08-29");
        selected = row.replace("2026-08-28", "2026-08-29");
        failsWithoutSaving();
    }

    @Test
    void doesNotReviseStoredPrice() throws Exception {
        when(repository.findLatest("XAUUSD", "twelve_data"))
                .thenReturn(Optional.of(stored("2026-08-28", "10")));
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-latest"))
                .andExpect(status().isServiceUnavailable());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void rejectsOlderQuote() throws Exception {
        when(repository.findLatest("XAUUSD", "twelve_data"))
                .thenReturn(Optional.of(stored("2026-08-31", "11")));
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-latest"))
                .andExpect(status().isServiceUnavailable());
        verify(repository, never()).saveAll(anyList());
    }

    private GoldDailyBar stored(String day, String close) {
        return new GoldDailyBar("XAUUSD", LocalDate.parse(day), new BigDecimal("10"),
                new BigDecimal("12"), new BigDecimal("9"), new BigDecimal(close),
                "usd", "troy_ounce", "twelve_data", OffsetDateTime.parse("2026-08-31T00:00:00Z"));
    }

    private void failsWithoutSaving() throws Exception {
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-latest"))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repository);
    }

    private void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
