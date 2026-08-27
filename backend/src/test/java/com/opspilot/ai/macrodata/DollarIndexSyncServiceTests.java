package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DollarIndexSyncServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-27T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("同步后统计新增修订未变化和缺失数量")
    void countsEverySaveResult() {
        DollarIndexProvider provider = mock(DollarIndexProvider.class);
        MacroObservationRepository repository = mock(MacroObservationRepository.class);
        IncomingMacroObservation first = observation("2026-08-19", "119.1");
        IncomingMacroObservation second = observation("2026-08-20", "119.2");
        IncomingMacroObservation third = observation("2026-08-21", "119.3");
        when(provider.fetchDailyObservations()).thenReturn(new DollarIndexBatch(
                List.of(first, second, third),
                4,
                1
        ));
        OffsetDateTime collectedAt = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(repository.save(first, collectedAt)).thenReturn(SaveObservationResult.INSERTED);
        when(repository.save(second, collectedAt)).thenReturn(SaveObservationResult.REVISED);
        when(repository.save(third, collectedAt)).thenReturn(SaveObservationResult.UNCHANGED);

        DollarIndexSyncResult result = new DollarIndexSyncService(
                provider,
                repository,
                CLOCK
        ).syncDailyObservations();

        assertThat(result).isEqualTo(new DollarIndexSyncResult(
                4, 1, 1, 1, 1, collectedAt
        ));
    }

    @Test
    @DisplayName("全部缺失时不写数据库")
    void skipsRepositoryWhenAllValuesAreMissing() {
        DollarIndexProvider provider = mock(DollarIndexProvider.class);
        MacroObservationRepository repository = mock(MacroObservationRepository.class);
        when(provider.fetchDailyObservations()).thenReturn(
                new DollarIndexBatch(List.of(), 2, 2)
        );

        DollarIndexSyncResult result = new DollarIndexSyncService(
                provider,
                repository,
                CLOCK
        ).syncDailyObservations();

        assertThat(result.insertedCount()).isZero();
        assertThat(result.missingCount()).isEqualTo(2);
        verify(repository, never()).save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private IncomingMacroObservation observation(String date, String value) {
        return new IncomingMacroObservation(
                "DTWEXBGS",
                LocalDate.parse(date),
                new BigDecimal(value),
                "index_2006_100",
                "fred"
        );
    }
}
