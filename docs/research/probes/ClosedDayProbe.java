import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.TwelveDataGoldBarProvider;
import com.opspilot.ai.marketdata.TwelveDataProperties;
import com.opspilot.ai.marketdata.MarketDataUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** 离线重放已保存的真实接口回执，验证生产获取链路；不请求外网或写数据库。 */
public class ClosedDayProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 2) {
            throw new IllegalArgumentException("需要普通日线和闭市确认两个原始回执路径");
        }
        ObjectMapper mapper = new ObjectMapper();
        boolean rejectsDuplicate = args.length == 1;
        JsonNode daily;
        JsonNode quote;
        Instant observed;
        if (rejectsDuplicate) {
            var receipt = mapper.readTree(Path.of(args[0]).toFile()).path("initial");
            if (!receipt.path("request").path("quote").path("eod").asBoolean()) {
                throw new IllegalArgumentException("缺少真实EOD请求元数据");
            }
            daily = mapper.createObjectNode().set("response", receipt.path("bars"));
            quote = mapper.createObjectNode().set("response", receipt.path("quote"));
            observed = java.time.OffsetDateTime.parse(receipt.path("completedAt").asText()).toInstant();
        } else {
            daily = mapper.readTree(Path.of(args[0]).toFile());
            quote = mapper.readTree(Path.of(args[1]).toFile());
            observed = Instant.parse(quote.path("fetchedAt").asText());
        }
        if (!rejectsDuplicate && (daily.path("httpStatus").asInt() != 200 || quote.path("httpStatus").asInt() != 200
                || !quote.path("request").path("eod").asBoolean())) {
            throw new IllegalArgumentException("回执不是成功的已结束交易日合同");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/time_series", exchange -> send(exchange, daily.get("response")));
        server.createContext("/quote", exchange -> {
            if (!exchange.getRequestURI().getRawQuery().contains("eod=true")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            send(exchange, quote.get("response"));
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var properties = new TwelveDataProperties(url, "offline-replay",
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            var provider = new TwelveDataGoldBarProvider(
                    RestClient.builder().baseUrl(url.toString()).build(), properties,
                    Clock.fixed(observed, ZoneOffset.UTC));
            if (rejectsDuplicate) {
                try {
                    provider.fetchDailyBars();
                    throw new AssertionError("真实重复日期回执未被生产链路拒绝");
                } catch (MarketDataUnavailableException rejected) {
                    if (!rejected.getMessage().contains("日期重复")) throw rejected;
                    System.out.println("PASS real-receipt rejection: duplicate-date; no market write");
                    return;
                }
            }
            List<GoldDailyBar> bars = provider.fetchDailyBars();
            // 这两个字面预期来自人工核实的真实回执日期，不复用生产筛选逻辑。
            if (!bars.stream().map(GoldDailyBar::priceDate).toList().equals(List.of(
                    LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-01")))) {
                throw new AssertionError("真实回执闭市边界重放未通过");
            }
            System.out.println("PASS real-receipt replay: retained=2, newest=2026-10-02; no market write");
        } finally {
            server.stop(0);
        }
    }

    private static void send(HttpExchange exchange, JsonNode response) throws java.io.IOException {
        byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
