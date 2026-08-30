package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldDailyBarSyncServiceTests {

    @Test
    void savesWeekdayBarsOnly() {
        TwelveDataGoldBarProvider provider =
                mock(TwelveDataGoldBarProvider.class);
        GoldDailyBarRepository repository =
                mock(GoldDailyBarRepository.class);
        GoldDailyBar friday = bar("2026-08-28", "4456.4");
        GoldDailyBar saturday = bar("2026-08-29", "4458.9");
        when(provider.fetchDailyBars())
                .thenReturn(List.of(saturday, friday));

        GoldDailyBarSyncResult result =
                new GoldDailyBarSyncService(provider, repository).sync();

        verify(repository).saveAll(List.of(friday));
        assertThat(result.receivedCount()).isEqualTo(2);
        assertThat(result.savedCount()).isEqualTo(1);
        assertThat(result.weekendSkippedCount()).isEqualTo(1);
        assertThat(result.latestPriceDate())
                .isEqualTo(LocalDate.parse("2026-08-28"));
    }

    private GoldDailyBar bar(String date, String close) {
        return new GoldDailyBar(
                "XAUUSD", LocalDate.parse(date),
                new BigDecimal("4450"), new BigDecimal("4460"),
                new BigDecimal("4440"), new BigDecimal(close),
                "usd", "troy_ounce", "twelve_data",
                OffsetDateTime.parse("2026-08-31T00:00:00Z")
        );
    }
}
