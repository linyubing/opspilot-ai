package com.opspilot.ai.marketdata;

import static org.assertj.core.api.Assertions.*;
import static com.opspilot.ai.marketdata.GoldSessionCheckResult.Status.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class GoldSessionCheckTests {
    private static final Instant START = Instant.parse("2026-04-03T20:00:00Z");
    private static final Instant END = Instant.parse("2026-04-04T21:00:00Z");
    private final GoldSessionCheck checker = new GoldSessionCheck();

    // 仅为数学样例：25根 open=10/high=12/low=8/close=11，不冒充行情。
    private List<GoldHourBar> hours() {
        var result = new ArrayList<GoldHourBar>();
        for (int i = 0; i < 25; i++) result.add(hour(START.plusSeconds(i * 3600L)));
        return result;
    }

    private GoldHourBar hour(Instant start) {
        return new GoldHourBar(start, n("10"), n("12"), n("8"), n("11"));
    }

    private BigDecimal n(String value) { return new BigDecimal(value); }

    private GoldDailyBar day() {
        return new GoldDailyBar("XAUUSD", LocalDate.of(2026, 4, 4), n("10"), n("12"),
                n("8"), n("11"), "usd", "troy_ounce", "twelve_data",
                OffsetDateTime.parse("2026-04-04T21:00:00Z"));
    }

    private GoldSessionCheckResult run(GoldDailyBar day, List<GoldHourBar> hours, Instant now) {
        var result = checker.check(day, hours, now);
        assertThat(result).isNotNull();
        return result;
    }

    @Test
    void matchesCompleteWindow() {
        var result = run(day(), hours(), END);
        assertThat(result.status()).isEqualTo(MATCHED);
        assertThat(result.matched()).isTrue();
        assertThat(result.expectedHours()).isEqualTo(25);
        assertThat(result.receivedRows()).isEqualTo(25);
        assertThat(result.missingHours()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-15,2026-01-14T20:00:00Z,2026-01-15T20:00:00Z,24",
        "2026-10-03,2026-10-02T21:00:00Z,2026-10-03T20:00:00Z,23"
    })
    void matchesOtherDurations(String label, String start, String end, int count) {
        var bars = new ArrayList<GoldHourBar>();
        for (int i = 0; i < count; i++) bars.add(hour(Instant.parse(start).plusSeconds(i * 3600L)));
        var day = copy(day(), LocalDate.parse(label), Instant.parse(end).atOffset(java.time.ZoneOffset.UTC));
        var result = run(day, bars, Instant.parse(end));
        assertThat(result.status()).isEqualTo(MATCHED);
        assertThat(result.expectedHours()).isEqualTo(count);
        assertThat(result.receivedRows()).isEqualTo(count);
    }

    @Test
    void identifiesSingleSourceGap() {
        var bars = hours();
        bars.removeIf(bar -> bar.start().equals(Instant.parse("2026-04-04T15:00:00Z")));
        var result = run(day(), bars, END);
        assertThat(result.status()).isEqualTo(MISSING_HOURS);
        assertThat(result.receivedRows()).isEqualTo(24);
        assertThat(result.missingHours()).containsExactly(Instant.parse("2026-04-04T15:00:00Z"));
    }

    @Test
    void reportsMissingHoursInOrder() {
        var bars = hours();
        bars.removeIf(bar -> bar.start().equals(Instant.parse("2026-04-04T15:00:00Z"))
                || bar.start().equals(Instant.parse("2026-04-04T01:00:00Z")));
        var result = run(day(), bars, END);
        assertThat(result.status()).isEqualTo(MISSING_HOURS);
        assertThat(result.matched()).isFalse();
        assertThat(result.expectedHours()).isEqualTo(25);
        assertThat(result.receivedRows()).isEqualTo(23);
        assertThat(result.missingHours()).containsExactly(
                Instant.parse("2026-04-04T01:00:00Z"), Instant.parse("2026-04-04T15:00:00Z"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> result.missingHours().add(START));
    }

    @Test
    void rejectsDuplicateReplacingMissing() {
        var bars = hours();
        bars.set(19, bars.getFirst());
        var result = run(day(), bars, END);
        assertThat(result.status()).isEqualTo(INVALID_INPUT);
        assertThat(result.receivedRows()).isEqualTo(25);
        assertThat(result.matched()).isFalse();
    }

    @Test
    void sortsWithoutChangingInput() {
        var bars = hours();
        Collections.reverse(bars);
        var before = List.copyOf(bars);
        assertThat(run(day(), bars, END).status()).isEqualTo(MATCHED);
        assertThat(bars).containsExactlyElementsOf(before);
    }

    @Test
    void rejectsUnfinishedWindow() {
        var day = copy(day(), day().priceDate(), START.atOffset(java.time.ZoneOffset.UTC));
        assertThat(run(day, hours(), END.minusNanos(1)).status()).isEqualTo(NOT_ENDED);
    }

    @Test
    void rejectsFutureCollection() {
        var day = copy(day(), day().priceDate(), END.plusSeconds(1).atOffset(java.time.ZoneOffset.UTC));
        assertThat(run(day, hours(), END).status()).isEqualTo(INVALID_INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-04-03T19:00:00Z", "2026-04-04T21:00:00Z",
            "2026-04-04T01:00:01Z", "2026-04-04T01:00:00.000000001Z"})
    void rejectsInvalidHour(String timestamp) {
        var bars = hours();
        bars.set(0, hour(Instant.parse(timestamp)));
        assertThat(run(day(), bars, END).status()).isEqualTo(INVALID_INPUT);
    }

    @Test
    void rejectsNullHour() {
        var bars = hours();
        bars.set(0, null);
        assertThat(run(day(), bars, END).status()).isEqualTo(INVALID_INPUT);
    }

    @Test
    void rejectsMissingDate() {
        assertThat(run(copy(day(), null, day().collectedAt()), hours(), END).status())
                .isEqualTo(INVALID_INPUT);
    }

    @Test
    void rejectsNullCalls() {
        assertThatIllegalArgumentException().isThrownBy(() -> checker.check(null, hours(), END));
        assertThatIllegalArgumentException().isThrownBy(() -> checker.check(day(), null, END));
        assertThatIllegalArgumentException().isThrownBy(() -> checker.check(day(), hours(), null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void comparesEachPriceExactly(int field) {
        var prices = new BigDecimal[]{n("10"), n("12"), n("8"), n("11")};
        prices[field] = prices[field].add(n("0.00000001"));
        var original = day();
        var changed = new GoldDailyBar(original.symbol(), original.priceDate(), prices[0], prices[1],
                prices[2], prices[3], original.currency(), original.unit(), original.provider(), original.collectedAt());
        assertThat(run(changed, hours(), END).status()).isEqualTo(OHLC_MISMATCH);
    }

    @Test
    void aggregatesBoundaryAndExtrema() {
        var bars = hours();
        bars.set(0, new GoldHourBar(START, n("9"), n("12"), n("8"), n("11")));
        bars.set(7, new GoldHourBar(START.plusSeconds(7 * 3600), n("10"), n("13"), n("7"), n("11")));
        bars.set(24, new GoldHourBar(END.minusSeconds(3600), n("10"), n("12"), n("8"), n("10.5")));
        var day = new GoldDailyBar("XAUUSD", LocalDate.of(2026, 4, 4), n("9.00"), n("13.00"),
                n("7.00"), n("10.500"), "usd", "troy_ounce", "twelve_data", day().collectedAt());
        assertThat(run(day, bars, END).status()).isEqualTo(MATCHED);
    }

    @Test
    void rejectsInvalidNativePrices() {
        var day = new GoldDailyBar("XAUUSD", day().priceDate(), n("0"), n("12"), n("8"),
                n("11"), "usd", "troy_ounce", "twelve_data", day().collectedAt());
        assertThat(run(day, hours(), END).status()).isEqualTo(INVALID_INPUT);
    }

    @Test
    void rejectsDifferentSource() {
        var day = new GoldDailyBar("OTHER", day().priceDate(), n("10"), n("12"), n("8"),
                n("11"), "usd", "troy_ounce", "twelve_data", day().collectedAt());
        assertThat(run(day, hours(), END).status()).isEqualTo(INVALID_INPUT);
    }

    @Test
    void rejectsMalformedHourPrices() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GoldHourBar(null, n("10"), n("12"), n("8"), n("11")));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoldHourBar(START, null, n("12"), n("8"), n("11")));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoldHourBar(START, n("0"), n("12"), n("8"), n("11")));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoldHourBar(START, n("10"), n("9"), n("8"), n("11")));
        assertThatIllegalArgumentException().isThrownBy(() -> new GoldHourBar(START, n("10"), n("12"), n("11"), n("11")));
    }

    private GoldDailyBar copy(GoldDailyBar day, LocalDate date, OffsetDateTime collectedAt) {
        return new GoldDailyBar(day.symbol(), date, day.open(), day.high(), day.low(), day.close(),
                day.currency(), day.unit(), day.provider(), collectedAt);
    }
}
