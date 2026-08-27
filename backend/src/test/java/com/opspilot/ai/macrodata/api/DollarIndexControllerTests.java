package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import com.opspilot.ai.macrodata.DollarIndexFreshness;
import com.opspilot.ai.macrodata.DollarIndexFreshnessEvaluator;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.DollarIndexSyncService;
import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DollarIndexControllerTests {

    private static final String SERIES_ID = "DTWEXBGS";
    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.parse("2026-08-27T01:00:00Z");

    private DollarIndexSyncService syncService;
    private MacroObservationRepository repository;
    private DollarIndexFreshnessEvaluator freshnessEvaluator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        syncService = mock(DollarIndexSyncService.class);
        repository = mock(MacroObservationRepository.class);
        freshnessEvaluator = mock(DollarIndexFreshnessEvaluator.class);
        mockMvc = standaloneSetup(new DollarIndexController(
                syncService,
                repository,
                freshnessEvaluator
        )).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("同步接口返回分类统计")
    void returnsSyncStatistics() throws Exception {
        when(syncService.syncDailyObservations()).thenReturn(
                new DollarIndexSyncResult(5, 1, 2, 1, 1, COLLECTED_AT)
        );

        mockMvc.perform(post("/api/macro-data/dollar-index/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(5))
                .andExpect(jsonPath("$.insertedCount").value(2));
    }

    @Test
    @DisplayName("最新接口返回当前数据及新鲜度")
    void returnsCurrentLatestObservation() throws Exception {
        MacroObservation observation = observation("2026-08-26", "119.12");
        when(repository.findLatest(SERIES_ID)).thenReturn(Optional.of(observation));
        when(freshnessEvaluator.evaluate(observation.observationDate()))
                .thenReturn(DollarIndexFreshness.CURRENT);

        mockMvc.perform(get("/api/macro-data/dollar-index/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesId").value(SERIES_ID))
                .andExpect(jsonPath("$.freshness").value("CURRENT"));
    }

    @Test
    @DisplayName("最新接口明确返回陈旧状态")
    void returnsStaleLatestObservation() throws Exception {
        MacroObservation observation = observation("2026-08-10", "118.50");
        when(repository.findLatest(SERIES_ID)).thenReturn(Optional.of(observation));
        when(freshnessEvaluator.evaluate(observation.observationDate()))
                .thenReturn(DollarIndexFreshness.STALE);

        mockMvc.perform(get("/api/macro-data/dollar-index/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshness").value("STALE"));
    }

    @Test
    @DisplayName("没有数据时最新接口返回稳定的 404 错误")
    void returnsNotFoundErrorWhenLatestIsMissing() throws Exception {
        when(repository.findLatest(SERIES_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/macro-data/dollar-index/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOLLAR_INDEX_NOT_FOUND"));
    }

    @Test
    @DisplayName("历史接口返回每条观测各自的新鲜度")
    void returnsRecentObservations() throws Exception {
        MacroObservation observation = observation("2026-08-25", "119.00");
        when(repository.findRecent(SERIES_ID, 20)).thenReturn(List.of(observation));
        when(freshnessEvaluator.evaluate(observation.observationDate()))
                .thenReturn(DollarIndexFreshness.CURRENT);

        mockMvc.perform(get("/api/macro-data/dollar-index/observations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value(119.00))
                .andExpect(jsonPath("$[0].freshness").value("CURRENT"));
        verify(repository).findRecent(SERIES_ID, 20);
    }

    @Test
    @DisplayName("历史接口拒绝越界 limit")
    void rejectsInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/macro-data/dollar-index/observations")
                        .param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MACRO_DATA_REQUEST"));
    }

    private MacroObservation observation(String date, String value) {
        return new MacroObservation(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                SERIES_ID,
                LocalDate.parse(date),
                new BigDecimal(value),
                "index_2006_100",
                "fred",
                COLLECTED_AT,
                null
        );
    }
}
