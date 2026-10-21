package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldSnapshotFixtures;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.marketdata.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 数学小时样例只验证准入规则，不用作行情和预测成绩。 */
class GoldBaseCheckTests {
    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private final GoldSessionCheckService sessions = mock(GoldSessionCheckService.class);
    private final GoldBaseCheck check = new GoldBaseCheck(sessions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void acceptsOriginalReplayableInput() {
        var snapshot = snapshot();
        var proof = proof(snapshot, NOW, false);
        when(sessions.check(snapshot.snapshot().input().bars().getFirst())).thenReturn(proof);
        assertThat(check.check(snapshot)).isSameAs(proof);
    }

    @Test void rejectsStaleAndFutureProof() {
        var snapshot = snapshot();
        for (var time : List.of(NOW.minusNanos(1), NOW.plusNanos(1))) {
            when(sessions.check(any(GoldDailyBar.class))).thenReturn(proof(snapshot, time, false));
            assertThatThrownBy(() -> check.check(snapshot)).isInstanceOf(MarketDataUnavailableException.class);
        }
    }

    @Test void replaysInsteadOfTrustingMatchedFlag() {
        var snapshot = snapshot();
        when(sessions.check(any(GoldDailyBar.class))).thenReturn(proof(snapshot, NOW, true));
        assertThatThrownBy(() -> check.check(snapshot)).isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test void rejectsMissingInputs() {
        var snapshot = snapshot();
        var p = proof(snapshot, NOW, false);
        when(sessions.check(any(GoldDailyBar.class))).thenReturn(new GoldSessionCheckResult(
                p.session(), p.checkedAt(), p.status(), p.expectedHours(), p.receivedRows(), List.of(), "无输入"));
        assertThatThrownBy(() -> check.check(snapshot)).isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test void rejectsProofForAnotherOriginalPrice() {
        var snapshot = snapshot();
        var other = GoldForecastTestFixtures.snapshot("2516.75");
        var replaced = new StoredGoldResearchSnapshot(other.id(), GoldSnapshotFixtures.withInput(other.snapshot(),
                other.snapshot().latestGoldDate(), "gold-multifactor-contiguous-v4", other.createdAt()), other.createdAt());
        when(sessions.check(any(GoldDailyBar.class))).thenReturn(proof(replaced, NOW, false));
        assertThatThrownBy(() -> check.check(snapshot)).isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test void hidesSourceError() {
        var snapshot = snapshot();
        when(sessions.check(any(GoldDailyBar.class))).thenThrow(new IllegalStateException("secret-api-key"));
        assertThatThrownBy(() -> check.check(snapshot)).isInstanceOf(MarketDataUnavailableException.class)
                .hasMessageNotContaining("secret-api-key").hasCause(null);
    }

    private StoredGoldResearchSnapshot snapshot() {
        var s = GoldForecastTestFixtures.snapshot("2515.75");
        return new StoredGoldResearchSnapshot(s.id(), GoldSnapshotFixtures.withInput(s.snapshot(),
                s.snapshot().latestGoldDate(), "gold-multifactor-contiguous-v4", s.createdAt()), s.createdAt());
    }

    private GoldSessionCheckResult proof(StoredGoldResearchSnapshot snapshot, Instant time, boolean incomplete) {
        var day = snapshot.snapshot().input().bars().getFirst();
        var session = GoldSession.forDate(day.priceDate());
        var hours = java.util.stream.LongStream.range(0, incomplete ? 23 : 24).mapToObj(i ->
                new GoldHourBar(session.start().plusSeconds(i * 3600), day.open(), day.high(), day.low(), day.close())).toList();
        return new GoldSessionCheckResult(session, time, GoldSessionCheckResult.Status.MATCHED,
                24, hours.size(), List.of(), "核验样例", new GoldSessionCheckResult.Input(day, hours, "REQUEST_ONLY_UTC"));
    }
}
