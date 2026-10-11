package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import com.opspilot.ai.marketdata.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 验证真实同步服务的有界补缺和整批停止；价格样例只验证边界。 */
class GoldRecentSyncTests {
    private final LocalDate latest = LocalDate.parse("2026-10-09");
    private TwelveDataGoldBarProvider provider;
    private GoldDailyBarRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        provider = mock(TwelveDataGoldBarProvider.class);
        repository = mock(GoldDailyBarRepository.class);
        when(provider.fetchLatestBar()).thenReturn(bar(latest));
        mvc = standaloneSetup(new GoldDailyBarController(
                new GoldDailyBarSyncService(provider, repository), repository))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("逐日补齐近期缺口且只走仅插入仓储，不请求全历史")
    void repairsMissingDays() throws Exception {
        var gaps = List.of(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-06"),
                LocalDate.parse("2026-10-08"), latest);
        when(repository.findRecent("XAUUSD", "twelve_data", latest, 80))
                .thenReturn(stored(gaps));
        when(provider.fetchDays(gaps)).thenReturn(gaps.stream().map(this::bar).toList());
        when(repository.saveMissing(anyList())).thenReturn(4);
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-recent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedCount").value(4))
                .andExpect(jsonPath("$.latestPriceDate").value("2026-10-09"));
        verify(repository, never()).saveAll(anyList());
        verify(provider, never()).fetchDailyBars();
    }

    @Test
    @DisplayName("任一缺日来源失败则不保存前面取得的部分行情")
    void stopsOnSourceFailure() throws Exception {
        var gaps = List.of(LocalDate.parse("2026-10-05"), latest);
        when(repository.findRecent("XAUUSD", "twelve_data", latest, 80)).thenReturn(stored(gaps));
        when(provider.fetchDays(gaps)).thenThrow(new MarketDataUnavailableException("缺日不可用"));
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-recent"))
                .andExpect(status().isServiceUnavailable());
        verify(repository, never()).saveMissing(anyList());
    }

    @Test
    @DisplayName("超过五个缺日停止自动请求，不挑成功子集凑窗口")
    void rejectsLargeGap() throws Exception {
        when(repository.findRecent("XAUUSD", "twelve_data", latest, 80)).thenReturn(List.of());
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-recent"))
                .andExpect(status().isServiceUnavailable());
        verify(provider, never()).fetchDays(anyList());
        verify(repository, never()).saveMissing(anyList());
    }

    @Test
    @DisplayName("已有连续窗口时幂等核验锚点，不重新获取已保存历史")
    void keepsCompleteWindow() throws Exception {
        when(repository.findRecent("XAUUSD", "twelve_data", latest, 80)).thenReturn(stored(List.of()));
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-recent"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedCount").value(0));
        verify(provider, never()).fetchDays(anyList());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("补缺返回缺条时整批停止，不保存来源子集")
    void rejectsPartialResponse() throws Exception {
        var gaps = List.of(LocalDate.parse("2026-10-05"), latest);
        when(repository.findRecent("XAUUSD", "twelve_data", latest, 80)).thenReturn(stored(gaps));
        when(provider.fetchDays(gaps)).thenReturn(List.of(bar(latest)));
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-recent"))
                .andExpect(status().isServiceUnavailable());
        verify(repository, never()).saveMissing(anyList());
    }

    @Test
    @DisplayName("最新日已存在时仍核对第二次锚点指纹，拒绝同日价格修订混批")
    void rejectsChangedAnchorProof() throws Exception {
        var gaps = List.of(LocalDate.parse("2026-10-05"));
        when(repository.findRecent("XAUUSD", "twelve_data", latest, 80)).thenReturn(stored(gaps));
        var old = bar(gaps.getFirst());
        var revised = new GoldDailyBar(old.symbol(), old.priceDate(), old.open(), old.high(), old.low(),
                old.close(), old.currency(), old.unit(), old.provider(), old.collectedAt(),
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, latest, old.collectedAt(), "cd".repeat(32)));
        when(provider.fetchDays(gaps)).thenReturn(List.of(revised));
        mvc.perform(post("/api/market-data/gold/daily-bars/sync-recent"))
                .andExpect(status().isServiceUnavailable());
        verify(repository, never()).saveMissing(anyList());
    }

    private List<GoldDailyBar> stored(List<LocalDate> gaps) {
        List<GoldDailyBar> rows = new ArrayList<>();
        LocalDate day = latest;
        for (int i = 0; i < 21; i++) {
            if (!gaps.contains(day)) rows.add(bar(day));
            do { day = day.minusDays(1); }
            while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY);
        }
        return rows;
    }

    private GoldDailyBar bar(LocalDate day) {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-11T01:00:00Z");
        return new GoldDailyBar("XAUUSD", day, BigDecimal.TEN, new BigDecimal("12"),
                new BigDecimal("9"), new BigDecimal("11"), "usd", "troy_ounce", "twelve_data", at,
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, latest, at, "ab".repeat(32)));
    }
}
