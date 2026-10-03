package com.opspilot.ai.marketdata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwelveDataGoldBarProviderHttpTests {

    private HttpServer server;
    private volatile String rawQuery;
    private volatile String quoteQuery;
    // 仅为接口合同测试的数学样例，不写入真实行情或模型评分。
    private String quote = """
            {"symbol":"XAU/USD","datetime":"2026-08-28",
             "open":"4601.3000","high":"4637.2000","low":"4444.6000",
             "close":"4456.4000","is_market_open":true}
            """;
    private String daily = """
            {"meta":{"symbol":"XAU/USD","interval":"1day"},
             "values":[{"datetime":"2026-08-31","open":"4601.3",
             "high":"4637.2","low":"4444.6","close":"4456.4"},
             {"datetime":"2026-08-28","open":"4601.3",
             "high":"4637.2","low":"4444.6","close":"4456.4"},
             {"datetime":"2026-08-27","open":"4601.3",
             "high":"4637.2","low":"4444.6","close":"4456.4"}],"status":"ok"}
            """;
    private int quoteStatus = 200;
    private Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:15:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/time_series", this::respond);
        server.createContext("/quote", exchange -> {
            quoteQuery = exchange.getRequestURI().getRawQuery();
            send(exchange, quote, quoteStatus);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void requestsDailyXauUsdBars() {
        List<GoldDailyBar> bars = provider().fetchDailyBars();

        assertThat(bars).extracting(bar -> bar.priceDate().toString())
                .containsExactly("2026-08-28", "2026-08-27");
        assertThat(rawQuery)
                .contains("symbol=XAU/USD")
                .contains("interval=1day")
                .contains("outputsize=5000")
                .contains("apikey=test-key");
        assertThat(quoteQuery).contains("symbol=XAU/USD", "eod=true", "apikey=test-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "high", "low", "close"})
    void rejectsPriceMismatch(String field) {
        String oldValue = switch (field) {
            case "open" -> "4601.3000";
            case "high" -> "4637.2000";
            case "low" -> "4444.6000";
            default -> "4456.4000";
        };
        quote = quote.replace(oldValue, switch (field) {
            case "open" -> "4602";
            case "high" -> "4638";
            case "low" -> "4443";
            default -> "4457";
        });
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("不一致");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "{\"status\":\"error\",\"message\":\"test-key\"}",
            "{\"symbol\":\"EUR/USD\",\"datetime\":\"2026-08-28\"}",
            "{\"symbol\":\"XAU/USD\",\"datetime\":\"2026-09-01\"}",
            "{\"symbol\":\"XAU/USD\",\"datetime\":\"invalid\"}"
    })
    void rejectsInvalidConfirmation(String response) {
        quote = response;
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageNotContaining("test-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "NaN", "Infinity", "", "-1"})
    void rejectsInvalidConfirmationPrice(String price) {
        quote = quote.replace("4444.6000", price);
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    void rejectsMissingAnchor() {
        quote = quote.replace("2026-08-28", "2026-08-26");
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("确认日");
    }

    @Test
    void rejectsUnavailableConfirmation() {
        quoteStatus = 503;
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageNotContaining("test-key")
                .hasNoCause();
    }

    @Test
    void rejectsNonDailyInterval() {
        daily = daily.replace("1day", "1h");
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    void rejectsDuplicateAnchor() {
        daily = daily.replace("2026-08-31", "2026-08-28");
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    void rejectsFutureConfirmationWithValidPrices() {
        quote = quote.replace("2026-08-28", "2026-09-01");
        daily = daily.replace("2026-08-28", "2026-09-01");
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("未来日期");
    }

    @Test
    void rejectsMalformedConfirmationDateWithValidPrices() {
        quote = quote.replace("2026-08-28", "invalid");
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("日期格式");
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "high", "low", "close", "datetime"})
    void rejectsMissingConfirmationField(String field) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(quote);
        node.remove(field);
        quote = node.toString();
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("缺少字段");
    }

    @Test
    void usesSupplierDayAcrossLocalMidnight() {
        // 同一时刻纽约仍是前一天，不用纽约日期把供应商显式确认日再次截断。
        clock = Clock.fixed(Instant.parse("2026-08-31T00:15:00Z"),
                java.time.ZoneId.of("America/New_York"));
        quote = quote.replace("2026-08-28", "2026-08-31");
        List<GoldDailyBar> bars = provider().fetchDailyBars();
        assertThat(bars).extracting(GoldDailyBar::priceDate)
                .contains(java.time.LocalDate.parse("2026-08-31"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4456.4", "true", "null", "[]", "{}"})
    void rejectsNonTextPrice(String value) {
        quote = quote.replace("\"close\":\"4456.4000\"", "\"close\":" + value);
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("字段类型")
                .hasNoCause();
    }

    @Test
    void rejectsNonTextDailyPrice() {
        daily = daily.replace("\"close\":\"4456.4\"", "\"close\":4456.4");
        assertThatThrownBy(() -> provider().fetchDailyBars())
                .isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageContaining("字段类型");
    }

    private TwelveDataGoldBarProvider provider() {
        URI baseUrl = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        TwelveDataProperties properties = new TwelveDataProperties(
                baseUrl, "test-key", Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        return new TwelveDataGoldBarProvider(
                        RestClient.builder().baseUrl(baseUrl.toString()).build(),
                        properties,
                        clock
                );
    }

    private void respond(HttpExchange exchange) throws IOException {
        rawQuery = exchange.getRequestURI().getRawQuery();
        send(exchange, daily, 200);
    }

    private void send(HttpExchange exchange, String json, int status) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
