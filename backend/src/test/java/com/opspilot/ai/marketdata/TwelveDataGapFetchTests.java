package com.opspilot.ai.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 验证有界补缺读取的 HTTP 合同；数学报价仅为测试，不连接真实行情或数据库。 */
class TwelveDataGapFetchTests {
    private static final String KEY = "gap-test-secret";
    private static final LocalDate CLOSED = LocalDate.parse("2026-10-09");
    private static final LocalDate FIRST = LocalDate.parse("2026-10-05");
    private static final LocalDate SECOND = LocalDate.parse("2026-10-06");
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private TwelveDataGoldBarProvider provider;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.invalid");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TwelveDataGoldBarProvider(builder.build(), properties(KEY), new StepClock());
    }

    @Test
    void fetchesAnchorFirstAndReturnsOnlyRequestedGapsWithOneFinalProof() {
        expectQuote(quote().toString());
        expectDay(CLOSED, daily(CLOSED).toString());
        var first = daily(FIRST);
        ((ObjectNode) first.path("values").get(0)).put("close", "10.5");
        var second = daily(SECOND);
        ((ObjectNode) second.path("values").get(0)).put("close", "10.6");
        expectDay(FIRST, first.toString());
        expectDay(SECOND, second.toString());

        List<GoldDailyBar> bars = fetch(List.of(FIRST, SECOND));

        assertThat(bars).extracting(GoldDailyBar::priceDate).containsExactly(FIRST, SECOND);
        assertThat(bars).extracting(GoldDailyBar::close)
                .containsExactly(new BigDecimal("10.5"), new BigDecimal("10.6"));
        var proof = bars.getFirst().confirmation();
        assertThat(proof.closedDay()).isEqualTo(CLOSED);
        assertThat(proof.source()).isEqualTo(GoldBarConfirmation.SOURCE);
        assertThat(proof.receiptHash()).matches("[0-9a-f]{64}");
        assertThat(proof.checkedAt()).isAfter(bars.getLast().collectedAt());
        assertThat(bars).allSatisfy(bar -> {
            assertThat(bar.confirmation()).isSameAs(proof);
            assertThat(bar.isConfirmedAt(proof.checkedAt())).isTrue();
        });
        server.verify();
    }

    @Test
    void reusesAnchorWhenItIsRequestedAndPreservesRequestedOrder() {
        expectQuote(quote().toString());
        expectDay(CLOSED, daily(CLOSED).toString());
        expectDay(FIRST, daily(FIRST).toString());

        assertThat(fetch(List.of(FIRST, CLOSED))).extracting(GoldDailyBar::priceDate)
                .containsExactly(FIRST, CLOSED);
        server.verify();
    }

    @Test
    void acceptsFiveRequestedDaysWithoutRepeatingAnchor() {
        var days = List.of(FIRST, SECOND, LocalDate.parse("2026-10-07"),
                LocalDate.parse("2026-10-08"), CLOSED);
        expectQuote(quote().toString());
        expectDay(CLOSED, daily(CLOSED).toString());
        days.stream().filter(day -> !day.equals(CLOSED))
                .forEach(day -> expectDay(day, daily(day).toString()));

        assertThat(fetch(days)).extracting(GoldDailyBar::priceDate).containsExactlyElementsOf(days);
        server.verify();
    }

    @Test
    void rejectsEmptyDaysBeforeHttp() {
        rejects(List.of());
        server.verify();
    }

    @Test
    void rejectsNullDaysBeforeHttp() {
        rejects(null);
        server.verify();
    }

    @Test
    void rejectsNullDayBeforeHttp() {
        rejects(Arrays.asList(FIRST, null));
        server.verify();
    }

    @Test
    void rejectsDuplicateDaysBeforeHttp() {
        rejects(List.of(FIRST, FIRST));
        server.verify();
    }

    @Test
    void rejectsMoreThanFiveDaysBeforeHttp() {
        rejects(List.of(FIRST, SECOND, LocalDate.parse("2026-10-07"),
                LocalDate.parse("2026-10-08"), CLOSED, LocalDate.parse("2026-10-01")));
        server.verify();
    }

    @Test
    void rejectsMissingApiKeyBeforeHttp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.invalid");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new TwelveDataGoldBarProvider(builder.build(), properties(" "), new StepClock());
        rejects(List.of(FIRST));
        server.verify();
    }

    @Test
    void rejectsRequestedDayAfterSupplierConfirmationBeforeDailyHttp() {
        expectQuote(quote().toString());
        rejects(List.of(FIRST, LocalDate.parse("2026-10-10")));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"status\":\"error\",\"message\":\"gap-test-secret\"}",
            "{\"symbol\":\"EUR/USD\",\"datetime\":\"2026-10-09\"}",
            "{\"symbol\":\"XAU/USD\",\"datetime\":\"2026-10-12\"}",
            "{\"symbol\":\"XAU/USD\",\"datetime\":\"invalid\"}"})
    void rejectsInvalidQuoteBeforeDailyHttp(String response) {
        expectQuote(response);
        rejects(List.of(FIRST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "high", "low", "close"})
    void rejectsAnchorMismatchForEveryOhlcFieldBeforeFetchingGaps(String field) {
        var quote = quote();
        quote.put(field, switch (field) {
            case "open" -> "10.1";
            case "high" -> "12.1";
            case "low" -> "8.9";
            default -> "11.1";
        });
        expectQuote(quote.toString());
        expectDay(CLOSED, daily(CLOSED).toString());
        rejects(List.of(FIRST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "extra", "wrong-day", "wrong-symbol", "wrong-interval",
            "numeric-price", "invalid-ohlc", "empty", "source-error", "malformed-json"})
    void rejectsInvalidAnchorBeforeFetchingGaps(String fault) {
        expectQuote(quote().toString());
        expectDay(CLOSED, faultyDaily(CLOSED, fault));
        rejects(List.of(FIRST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "extra", "wrong-day", "wrong-symbol", "wrong-interval",
            "numeric-price", "invalid-ohlc", "empty", "source-error", "malformed-json"})
    void rejectsInvalidGapWithoutReturningPartialBatch(String fault) {
        expectQuote(quote().toString());
        expectDay(CLOSED, daily(CLOSED).toString());
        expectDay(FIRST, daily(FIRST).toString());
        expectDay(SECOND, faultyDaily(SECOND, fault));
        rejects(List.of(FIRST, SECOND));
        server.verify();
    }

    @Test
    void sanitizesQuoteTransportFailure() {
        server.expect(requestTo(startsWith("https://example.invalid/quote?")))
                .andRespond(withException(new IOException("apikey=" + KEY)));
        rejects(List.of(FIRST));
        server.verify();
    }

    @Test
    void sanitizesLateGapTransportFailureWithoutReturningPartialBatch() {
        expectQuote(quote().toString());
        expectDay(CLOSED, daily(CLOSED).toString());
        expectDay(FIRST, daily(FIRST).toString());
        server.expect(requestTo(startsWith("https://example.invalid/time_series?")))
                .andExpect(queryParam("date", SECOND.toString()))
                .andRespond(withException(new IOException("apikey=" + KEY)));
        rejects(List.of(FIRST, SECOND));
        server.verify();
    }

    private void rejects(List<LocalDate> days) {
        assertThatThrownBy(() -> fetch(days)).isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageNotContaining(KEY).hasMessageNotContaining("apikey=").hasNoCause();
    }

    private List<GoldDailyBar> fetch(List<LocalDate> days) {
        return provider.fetchDays(days);
    }

    private TwelveDataProperties properties(String key) {
        return new TwelveDataProperties(URI.create("https://example.invalid"), key,
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private ObjectNode row(LocalDate day) {
        return mapper.createObjectNode().put("datetime", day.toString()).put("open", "10")
                .put("high", "12").put("low", "9").put("close", "11");
    }

    private ObjectNode quote() {
        return row(CLOSED).put("symbol", "XAU/USD");
    }

    private ObjectNode daily(LocalDate day) {
        var root = mapper.createObjectNode().put("status", "ok");
        root.putObject("meta").put("symbol", "XAU/USD").put("interval", "1day");
        root.putArray("values").add(row(day));
        return root;
    }

    private String faultyDaily(LocalDate day, String fault) {
        if (fault.equals("malformed-json")) {
            return "{invalid \"apikey=" + KEY + "\"}";
        }
        var root = daily(day);
        switch (fault) {
            case "duplicate" -> root.withArray("values").add(row(day).put("close", "10.5"));
            case "extra" -> root.withArray("values").add(row(day.minusDays(1)));
            case "wrong-day" -> ((ObjectNode) root.path("values").get(0))
                    .put("datetime", day.minusDays(1).toString());
            case "wrong-symbol" -> ((ObjectNode) root.path("meta")).put("symbol", "EUR/USD");
            case "wrong-interval" -> ((ObjectNode) root.path("meta")).put("interval", "1h");
            case "numeric-price" -> ((ObjectNode) root.path("values").get(0)).put("close", 11);
            case "invalid-ohlc" -> ((ObjectNode) root.path("values").get(0)).put("low", "13");
            case "empty" -> root.withArray("values").removeAll();
            case "source-error" -> root.put("status", "error").put("message", "apikey=" + KEY);
            default -> throw new AssertionError("Unknown test fault " + fault);
        }
        return root.toString();
    }

    private void expectQuote(String response) {
        server.expect(requestTo(startsWith("https://example.invalid/quote?")))
                .andExpect(queryParam("symbol", "XAU/USD")).andExpect(queryParam("eod", "true"))
                .andExpect(queryParam("apikey", KEY))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void expectDay(LocalDate day, String response) {
        server.expect(requestTo(startsWith("https://example.invalid/time_series?")))
                .andExpect(queryParam("symbol", "XAU/USD")).andExpect(queryParam("interval", "1day"))
                .andExpect(queryParam("date", day.toString())).andExpect(queryParam("apikey", KEY))
                .andExpect(request -> assertThat(request.getURI().getQuery())
                        .doesNotContain("start_date=", "end_date=", "outputsize="))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static final class StepClock extends Clock {
        private int ticks;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return Instant.parse("2026-10-11T00:00:00Z").plusSeconds(ticks++);
        }
    }
}
