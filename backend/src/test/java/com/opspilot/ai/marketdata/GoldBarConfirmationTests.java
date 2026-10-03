package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class GoldBarConfirmationTests {
    private static final OffsetDateTime CHECKED = OffsetDateTime.parse("2026-08-31T00:00:00Z");
    private static final String HASH = "ab".repeat(32);

    @Test
    void acceptsConfirmedBarAtSameInstant() {
        var proof = new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-08-28"), CHECKED, HASH);
        assertThat(bar(proof, CHECKED).isConfirmedAt(
                OffsetDateTime.parse("2026-08-31T08:00:00+08:00"))).isTrue();
    }

    @Test
    void keepsLegacyBarUnconfirmed() {
        var legacy = new GoldDailyBar("XAUUSD", LocalDate.parse("2026-08-28"),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                "usd", "troy_ounce", "twelve_data", CHECKED);
        assertThat(legacy.isConfirmedAt(CHECKED.plusDays(5))).isFalse();
    }

    @Test
    void rejectsFutureProofAndCollection() {
        var proof = new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-08-28"), CHECKED, HASH);
        assertThat(bar(proof, CHECKED).isConfirmedAt(CHECKED.minusNanos(1))).isFalse();
        assertThat(bar(proof, CHECKED.plusNanos(1)).isConfirmedAt(CHECKED.plusDays(1))).isFalse();
    }

    @Test
    void rejectsBoundaryBeforeBar() {
        var proof = new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-08-27"), CHECKED, HASH);
        assertThat(bar(proof, CHECKED).isConfirmedAt(CHECKED)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "test", "aabb", "gg"})
    void rejectsInvalidHash(String hash) {
        var proof = new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-08-28"), CHECKED, hash);
        assertThat(bar(proof, CHECKED).isConfirmedAt(CHECKED)).isFalse();
    }

    @Test
    void rejectsWrongContract() {
        var proof = new GoldBarConfirmation("twelve_data_eod",
                LocalDate.parse("2026-08-28"), CHECKED, HASH);
        assertThat(bar(proof, CHECKED).isConfirmedAt(CHECKED)).isFalse();
    }

    @Test
    void rejectsIncompleteProof() {
        assertThat(bar(new GoldBarConfirmation(GoldBarConfirmation.SOURCE, null, CHECKED, HASH),
                CHECKED).isConfirmedAt(CHECKED)).isFalse();
        assertThat(bar(new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-08-28"), null, HASH), CHECKED).isConfirmedAt(CHECKED)).isFalse();
        assertThat(bar(new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-08-28"), CHECKED, null), CHECKED).isConfirmedAt(CHECKED)).isFalse();
    }

    @Test
    void proofCannotBecomeValidJustBecauseTimePasses() {
        var proof = new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                LocalDate.parse("2026-09-01"), CHECKED, HASH);
        assertThat(bar(proof, CHECKED).isConfirmedAt(CHECKED.plusDays(2))).isFalse();
    }

    private GoldDailyBar bar(GoldBarConfirmation proof, OffsetDateTime collected) {
        return new GoldDailyBar("XAUUSD", LocalDate.parse("2026-08-28"),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                "usd", "troy_ounce", "twelve_data", collected, proof);
    }
}
