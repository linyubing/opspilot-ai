package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldBarConfirmation;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;
import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GoldDailyBarControllerTests {

    private GoldDailyBarSyncService sync;
    private GoldDailyBarRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sync = mock(GoldDailyBarSyncService.class);
        repository = mock(GoldDailyBarRepository.class);
        mvc = standaloneSetup(new GoldDailyBarController(sync, repository,
                Clock.fixed(Instant.parse("2026-08-31T01:00:00Z"), ZoneOffset.UTC)))
                .build();
    }

    @Test
    void getsLatestOhlc() throws Exception {
        when(repository.findLatest("XAUUSD", "twelve_data"))
                .thenReturn(Optional.of(bar()));

        mvc.perform(get("/api/market-data/gold/daily-bars/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceDate").value("2026-08-28"))
                .andExpect(jsonPath("$.open").value(4601.3))
                .andExpect(jsonPath("$.high").value(4637.2))
                .andExpect(jsonPath("$.low").value(4444.6))
                .andExpect(jsonPath("$.close").value(4456.4));
    }

    @Test
    void syncsOhlc() throws Exception {
        when(sync.sync()).thenReturn(new GoldDailyBarSyncResult(
                100, 72, 28, LocalDate.parse("2026-08-28")
        ));

        mvc.perform(post("/api/market-data/gold/daily-bars/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedCount").value(72))
                .andExpect(jsonPath("$.latestPriceDate")
                        .value("2026-08-28"));
    }

    @Test
    void hidesUnconfirmedPrice() throws Exception {
        GoldDailyBar confirmed = bar();
        GoldDailyBar raw = new GoldDailyBar(confirmed.symbol(), confirmed.priceDate(),
                confirmed.open(), confirmed.high(), confirmed.low(), confirmed.close(),
                confirmed.currency(), confirmed.unit(), confirmed.provider(), confirmed.collectedAt());
        when(repository.findLatest("XAUUSD", "twelve_data")).thenReturn(Optional.of(raw));

        mvc.perform(get("/api/market-data/gold/daily-bars/latest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesFutureConfirmation() throws Exception {
        GoldDailyBar base = bar();
        GoldDailyBar future = new GoldDailyBar(base.symbol(), base.priceDate(), base.open(),
                base.high(), base.low(), base.close(), base.currency(), base.unit(), base.provider(),
                base.collectedAt(), new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                base.priceDate(), OffsetDateTime.parse("2099-01-01T00:00:00Z"), "a".repeat(64)));
        when(repository.findLatest("XAUUSD", "twelve_data")).thenReturn(Optional.of(future));

        mvc.perform(get("/api/market-data/gold/daily-bars/latest"))
                .andExpect(status().isNotFound());
    }

    private GoldDailyBar bar() {
        return new GoldDailyBar(
                "XAUUSD", LocalDate.parse("2026-08-28"),
                new BigDecimal("4601.3"), new BigDecimal("4637.2"),
                new BigDecimal("4444.6"), new BigDecimal("4456.4"),
                "usd", "troy_ounce", "twelve_data",
                OffsetDateTime.parse("2026-08-31T00:00:00Z"),
                // 明确标记数学样例，不是供应商真实确认回执。
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, LocalDate.parse("2026-08-28"),
                        OffsetDateTime.parse("2026-08-31T00:00:00Z"), "a".repeat(64))
        );
    }
}
