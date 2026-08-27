package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FredDollarIndexProviderTests {

    @Test
    @DisplayName("把通用 FRED 观测映射为广义美元指数领域数据")
    void mapsFredObservationsToDollarIndex() {
        FredSeriesClient seriesClient = mock(FredSeriesClient.class);
        FredProperties properties = properties();
        when(seriesClient.fetch("DTWEXBGS")).thenReturn(new FredSeriesBatch(
                List.of(
                        new FredSeriesObservation(
                                LocalDate.parse("2026-08-19"),
                                new BigDecimal("119.1234")
                        ),
                        new FredSeriesObservation(
                                LocalDate.parse("2026-08-20"),
                                new BigDecimal("119.5678")
                        )
                ),
                3,
                1
        ));

        DollarIndexBatch batch = new FredDollarIndexProvider(
                seriesClient,
                properties
        ).fetchDailyObservations();

        verify(seriesClient).fetch("DTWEXBGS");
        assertThat(batch.receivedCount()).isEqualTo(3);
        assertThat(batch.missingCount()).isEqualTo(1);
        assertThat(batch.observations()).hasSize(2).allSatisfy(observation -> {
            assertThat(observation.seriesId()).isEqualTo("DTWEXBGS");
            assertThat(observation.unit()).isEqualTo("index_2006_100");
            assertThat(observation.provider()).isEqualTo("fred");
        });
    }

    private FredProperties properties() {
        return new FredProperties(
                URI.create("https://api.stlouisfed.org"),
                "test-key",
                "DFII10",
                "DTWEXBGS",
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)
        );
    }
}
