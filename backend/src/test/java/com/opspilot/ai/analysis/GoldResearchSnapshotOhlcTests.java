package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldBarWindow;
import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldResearchSnapshotOhlcTests {

    private static final LocalDate AS_OF = LocalDate.parse("2026-08-28");
    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.parse("2026-08-30T18:31:42Z");
    private final ConfiguredGoldTradingCalendar calendar = calendar();

    @Test
    void usesCloseAsCurrentGoldPrice() {
        GoldDailyBarRepository bars = mock(GoldDailyBarRepository.class);
        MacroObservationRepository macro =
                mock(MacroObservationRepository.class);
        when(bars.findRecent("XAUUSD", "twelve_data", AS_OF, 120))
                .thenReturn(goldBars());
        when(macro.findRecent("DFII10", AS_OF, 120))
                .thenReturn(observations("DFII10", "2.10"));
        when(macro.findRecent("DTWEXBGS", AS_OF, 120))
                .thenReturn(observations("DTWEXBGS", "120"));

        GoldResearchSnapshotService service =
                new GoldResearchSnapshotService(
                        bars,
                        macro,
                        new RealRateFactorEvaluator(),
                        new DollarIndexFactorEvaluator(),
                        Clock.fixed(COLLECTED_AT.toInstant(), ZoneOffset.UTC),
                        new GoldBarWindow(calendar)
                );

        GoldResearchSnapshot snapshot = service.createSnapshot(AS_OF);

        assertThat(snapshot.gold().currentPrice())
                .isEqualByComparingTo("4456");
        assertThat(snapshot.gold().collectedAt())
                .isEqualTo(COLLECTED_AT);
    }

    private List<GoldDailyBar> goldBars() {
        List<GoldDailyBar> bars = new ArrayList<>();
        LocalDate day = AS_OF;
        for (int index = 0; index < 21; index++) {
            BigDecimal close = new BigDecimal("4456").subtract(
                    BigDecimal.valueOf(index * 10L)
            );
            BigDecimal open = index == 0 ? new BigDecimal("4601") : close;
            bars.add(new GoldDailyBar(
                    "XAUUSD",
                    day,
                    open,
                    open.max(close).add(new BigDecimal("20")),
                    close.subtract(new BigDecimal("20")),
                    close,
                    "usd",
                    "troy_ounce",
                    "twelve_data",
                    COLLECTED_AT,
                    // 合成算法夹具，不代表供应商实测确认。
                    new GoldBarConfirmation(GoldBarConfirmation.SOURCE, AS_OF,
                            COLLECTED_AT, "a".repeat(64))
            ));
            day = previousTradingDay(day);
        }
        return bars;
    }

    private LocalDate previousTradingDay(LocalDate from) {
        LocalDate day = from.minusDays(1);
        while (!calendar.nextBusinessDay(day.minusDays(1)).equals(day)) {
            day = day.minusDays(1);
        }
        return day;
    }

    private static ConfiguredGoldTradingCalendar calendar() {
        ConfiguredGoldTradingCalendar calendar = new ConfiguredGoldTradingCalendar();
        calendar.setHolidays(Set.of("--01-01", "--07-04", "--12-25"));
        return calendar;
    }

    private List<MacroObservation> observations(
            String seriesId,
            String value
    ) {
        List<MacroObservation> items = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            items.add(new MacroObservation(
                    UUID.randomUUID(),
                    seriesId,
                    AS_OF.minusDays(index),
                    new BigDecimal(value),
                    "value",
                    "fred",
                    COLLECTED_AT,
                    null
            ));
        }
        return items;
    }
}
