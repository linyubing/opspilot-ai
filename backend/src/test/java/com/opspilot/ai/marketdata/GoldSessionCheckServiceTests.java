package com.opspilot.ai.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.opspilot.ai.marketdata.GoldSessionCheckResult.Status.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class GoldSessionCheckServiceTests {
    private static final LocalDate DATE = LocalDate.of(2026, 4, 4);
    private static final Instant NOW = Instant.parse("2026-04-05T00:00:00Z");
    private static final String KEY = "session-test-secret";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private GoldSessionCheckService service;
    private ObjectNode daily;
    private ObjectNode hourly;
    private final CountingClock clock = new CountingClock();

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.invalid");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GoldSessionCheckService(builder.build(), new TwelveDataProperties(
                URI.create("https://example.invalid"), KEY, Duration.ofSeconds(2), Duration.ofSeconds(2)),
                clock, new GoldSessionCheck());
        // 数学HTTP样例，不访问真实行情、不写库。
        daily = root("1day", List.of(row("2026-04-04")));
        var rows = new ArrayList<ObjectNode>();
        for (int i = 0; i < 25; i++) rows.add(row(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.parse("2026-04-03T20:00:00Z").plusSeconds(i * 3600L))));
        hourly = root("1h", rows);
    }

    private ObjectNode row(String time) {
        return mapper.createObjectNode().put("datetime", time).put("open", "10.00000000")
                .put("high", "12.00000000").put("low", "8.00000000").put("close", "11.00000000");
    }

    private ObjectNode root(String interval, List<ObjectNode> rows) {
        ObjectNode result = mapper.createObjectNode().put("status", "ok");
        result.putObject("meta").put("symbol", "XAU/USD").put("interval", interval);
        var values = result.putArray("values");
        rows.forEach(values::add);
        return result;
    }

    private void expectDaily() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://example.invalid/time_series?")))
                .andExpect(queryParam("symbol", "XAU/USD")).andExpect(queryParam("interval", "1day"))
                .andExpect(queryParam("start_date", "2026-04-04")).andExpect(queryParam("end_date", "2026-04-05"))
                .andExpect(queryParam("outputsize", "2")).andExpect(queryParam("apikey", KEY))
                .andRespond(withSuccess(daily.toString(), MediaType.APPLICATION_JSON));
    }

    private void expectHourly() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://example.invalid/time_series?")))
                .andExpect(queryParam("interval", "1h")).andExpect(queryParam("symbol", "XAU/USD"))
                .andExpect(queryParam("timezone", "UTC")).andExpect(queryParam("outputsize", "64"))
                .andExpect(queryParam("start_date", "2026-04-03T20:00:00"))
                .andExpect(queryParam("end_date", "2026-04-04T21:00:00"))
                .andRespond(withSuccess(hourly.toString(), MediaType.APPLICATION_JSON));
    }

    private GoldSessionCheckResult run() {
        expectDaily(); expectHourly();
        var result = service.check(DATE);
        assertThat(result).isNotNull();
        server.verify();
        return result;
    }

    @Test
    void checksExactWindowAndObservesOnce() {
        var result = run();
        assertThat(result.status()).isEqualTo(MATCHED);
        assertThat(result.expectedHours()).isEqualTo(25);
        assertThat(result.receivedRows()).isEqualTo(25);
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(clock.calls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectsUniqueTargetWithEndLabel(boolean reverse) {
        daily = root("1day", reverse ? List.of(row("2026-04-05"), row("2026-04-04"))
                : List.of(row("2026-04-04"), row("2026-04-05")));
        assertThatNoException().isThrownBy(() -> assertThat(run().status()).isEqualTo(MATCHED));
    }

    @Test
    void dropsOnlyExactEndRow() {
        hourly.withArray("values").add(row("2026-04-04 21:00:00"));
        assertThat(run().status()).isEqualTo(MATCHED);
    }

    @Test
    void rejectsRepeatedEndRow() {
        hourly.withArray("values").add(row("2026-04-04 21:00:00"));
        hourly.withArray("values").add(row("2026-04-04 21:00:00"));
        expectDaily(); expectHourly();
        assertThatThrownBy(() -> service.check(DATE)).isInstanceOf(MarketDataUnavailableException.class)
                .hasNoCause();
    }

    @Test
    void rejectsExplicitOutputZoneConflict() {
        ((ObjectNode) hourly.path("meta")).put("timezone", "Europe/London");
        expectDaily(); expectHourly();
        assertThatThrownBy(() -> service.check(DATE)).isInstanceOf(MarketDataUnavailableException.class)
                .hasNoCause();
    }

    @Test
    void doesNotConfuseExchangeZoneWithOutputZone() {
        ((ObjectNode) hourly.path("meta")).put("exchange_timezone", "Australia/Sydney");
        assertThat(run().status()).isEqualTo(MATCHED);
    }

    @Test
    void retainsMissingHour() {
        hourly.withArray("values").remove(19);
        var result = run();
        assertThat(result.status()).isEqualTo(MISSING_HOURS);
        assertThat(result.missingHours()).containsExactly(Instant.parse("2026-04-04T15:00:00Z"));
    }

    @Test
    void detectsPriceDifference() {
        ((ObjectNode) daily.path("values").get(0)).put("open", "10.00000001");
        assertThat(run().status()).isEqualTo(OHLC_MISMATCH);
    }

    @Test
    void preservesDuplicateForRejection() {
        hourly.withArray("values").set(19, hourly.path("values").get(0).deepCopy());
        assertThat(run().status()).isEqualTo(INVALID_INPUT);
    }

    @Test
    void preservesOtherOutOfRangeForRejection() {
        hourly.withArray("values").add(row("2026-04-04 22:00:00"));
        assertThat(run().status()).isEqualTo(INVALID_INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "symbol", "interval", "empty", "duplicate", "otherDate", "badDate", "numericPrice", "badPrice"})
    void rejectsBadDaily(String issue) {
        switch (issue) {
            case "status" -> daily.put("status", "error").put("message", KEY);
            case "symbol" -> ((ObjectNode) daily.path("meta")).put("symbol", "EUR/USD");
            case "interval" -> ((ObjectNode) daily.path("meta")).put("interval", "1h");
            case "empty" -> daily.withArray("values").removeAll();
            case "duplicate" -> daily.withArray("values").add(row("2026-04-04"));
            case "otherDate" -> ((ObjectNode) daily.path("values").get(0)).put("datetime", "2026-04-03");
            case "badDate" -> ((ObjectNode) daily.path("values").get(0)).put("datetime", "2026-02-30");
            case "numericPrice" -> ((ObjectNode) daily.path("values").get(0)).put("high", 12);
            default -> ((ObjectNode) daily.path("values").get(0)).put("high", "9");
        }
        expectDaily();
        assertThatThrownBy(() -> service.check(DATE)).isInstanceOf(MarketDataUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "symbol", "interval", "empty", "badTime", "numericPrice", "badPrice"})
    void rejectsBadHourly(String issue) {
        switch (issue) {
            case "status" -> hourly.put("status", "error").put("message", KEY);
            case "symbol" -> ((ObjectNode) hourly.path("meta")).put("symbol", "OTHER");
            case "interval" -> ((ObjectNode) hourly.path("meta")).put("interval", "1day");
            case "empty" -> hourly.withArray("values").removeAll();
            case "badTime" -> ((ObjectNode) hourly.path("values").get(0)).put("datetime", "2026-02-30 20:00:00");
            case "numericPrice" -> ((ObjectNode) hourly.path("values").get(0)).put("open", 10);
            default -> ((ObjectNode) hourly.path("values").get(0)).put("low", "13");
        }
        expectDaily(); expectHourly();
        assertThatThrownBy(() -> service.check(DATE)).isInstanceOf(MarketDataUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(KEY);
    }

    @Test
    void hidesHttpFailure(CapturedOutput output) {
        expectDaily();
        server.expect(anything()).andRespond(withException(new java.io.IOException("apikey=" + KEY)));
        assertThatThrownBy(() -> service.check(DATE)).isInstanceOf(MarketDataUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(KEY).hasMessageNotContaining("https://");
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    @Test
    void rejectsMissingKeyWithoutRequest() {
        var client = RestClient.builder().baseUrl("https://example.invalid").build();
        var noKey = new GoldSessionCheckService(client, new TwelveDataProperties(URI.create("https://example.invalid"),
                "", Duration.ofSeconds(1), Duration.ofSeconds(1)), clock, new GoldSessionCheck());
        assertThatThrownBy(() -> noKey.check(DATE)).isInstanceOf(MarketDataUnavailableException.class);
        assertThat(clock.calls).isZero();
    }

    private static class CountingClock extends Clock {
        int calls;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { calls++; return NOW; }
    }
}
