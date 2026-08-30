package com.opspilot.ai.marketdata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TwelveDataGoldBarProviderHttpTests {

    private HttpServer server;
    private volatile String rawQuery;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/time_series", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void requestsDailyXauUsdBars() {
        URI baseUrl = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        TwelveDataProperties properties = new TwelveDataProperties(
                baseUrl, "test-key", Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        TwelveDataGoldBarProvider provider =
                new TwelveDataGoldBarProvider(
                        RestClient.builder().baseUrl(baseUrl.toString()).build(),
                        properties,
                        Clock.systemUTC()
                );

        List<GoldDailyBar> bars = provider.fetchDailyBars();

        assertThat(bars).hasSize(1);
        assertThat(rawQuery)
                .contains("symbol=XAU/USD")
                .contains("interval=1day")
                .contains("outputsize=5000")
                .contains("apikey=test-key");
    }

    private void respond(HttpExchange exchange) throws IOException {
        rawQuery = exchange.getRequestURI().getRawQuery();
        byte[] body = """
                {"meta":{"symbol":"XAU/USD","interval":"1day"},
                 "values":[{"datetime":"2026-08-28","open":"4601.3",
                 "high":"4637.2","low":"4444.6","close":"4456.4"}],
                 "status":"ok"}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
