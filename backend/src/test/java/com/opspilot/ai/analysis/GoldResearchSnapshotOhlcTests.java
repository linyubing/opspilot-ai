package com.opspilot.ai.analysis;

import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoldResearchSnapshotOhlcTests {

    private static final LocalDate AS_OF = LocalDate.parse("2026-08-28");
    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.parse("2026-08-30T18:31:42Z");

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
                        new DollarIndexFactorEvaluator()
                );

        GoldResearchSnapshot snapshot = service.createSnapshot(AS_OF);

        assertThat(snapshot.gold().currentPrice())
                .isEqualByComparingTo("4456");
        assertThat(snapshot.gold().collectedAt())
                .isEqualTo(COLLECTED_AT);
    }

    private List<GoldDailyBar> goldBars() {
        List<GoldDailyBar> bars = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            BigDecimal close = new BigDecimal("4456").subtract(
                    BigDecimal.valueOf(index * 10L)
            );
            bars.add(new GoldDailyBar(
                    "XAUUSD",
                    AS_OF.minusDays(index),
                    index == 0 ? new BigDecimal("4601") : close,
                    close.add(new BigDecimal("20")),
                    close.subtract(new BigDecimal("20")),
                    close,
                    "usd",
                    "troy_ounce",
                    "twelve_data",
                    COLLECTED_AT
            ));
        }
        return bars;
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
