package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import com.opspilot.ai.macrodata.MacroDataUnavailableException;
import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RealRateControllerTests {

    private static final String SERIES_ID = "DFII10";
    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.parse("2026-08-26T01:00:00Z");

    private RealRateSyncService syncService;
    private MacroObservationRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        syncService = mock(RealRateSyncService.class);
        repository = mock(MacroObservationRepository.class);
        mockMvc = standaloneSetup(
                new RealRateController(syncService, repository)
        ).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("同步接口返回分类统计")
    void returnsSyncStatistics() throws Exception {
        when(syncService.syncDailyObservations()).thenReturn(
                new RealRateSyncResult(5, 1, 2, 1, 1, COLLECTED_AT)
        );

        mockMvc.perform(post("/api/macro-data/real-rate/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(5))
                .andExpect(jsonPath("$.missingCount").value(1))
                .andExpect(jsonPath("$.insertedCount").value(2))
                .andExpect(jsonPath("$.revisedCount").value(1))
                .andExpect(jsonPath("$.unchangedCount").value(1));
    }

    @Test
    @DisplayName("最新接口返回当前观测且不暴露内部版本字段")
    void returnsLatestObservation() throws Exception {
        when(repository.findLatest(SERIES_ID))
                .thenReturn(Optional.of(observation("2026-08-25", "1.82")));

        mockMvc.perform(get("/api/macro-data/real-rate/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesId").value(SERIES_ID))
                .andExpect(jsonPath("$.value").value(1.82))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.supersededAt").doesNotExist());
    }

    @Test
    @DisplayName("没有实际利率数据时最新接口返回 404")
    void returnsNotFoundWhenLatestIsMissing() throws Exception {
        when(repository.findLatest(SERIES_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/macro-data/real-rate/latest"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("历史接口把显式 limit 传给仓储")
    void passesExplicitLimitToRepository() throws Exception {
        when(repository.findRecent(SERIES_ID, 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/macro-data/real-rate").param("limit", "5"))
                .andExpect(status().isOk());

        verify(repository).findRecent(SERIES_ID, 5);
    }

    @Test
    @DisplayName("历史接口默认查询最近 60 条")
    void usesDefaultLimit() throws Exception {
        when(repository.findRecent(SERIES_ID, 60)).thenReturn(List.of());

        mockMvc.perform(get("/api/macro-data/real-rate"))
                .andExpect(status().isOk());

        verify(repository).findRecent(SERIES_ID, 60);
    }

    @Test
    @DisplayName("历史接口允许查询上限 500 条")
    void acceptsMaximumLimit() throws Exception {
        when(repository.findRecent(SERIES_ID, 500)).thenReturn(List.of());

        mockMvc.perform(get("/api/macro-data/real-rate").param("limit", "500"))
                .andExpect(status().isOk());

        verify(repository).findRecent(SERIES_ID, 500);
    }

    @Test
    @DisplayName("limit 越界时返回宏观请求错误")
    void rejectsOutOfRangeLimits() throws Exception {
        for (int limit : List.of(-1, 0, 501)) {
            mockMvc.perform(get("/api/macro-data/real-rate")
                            .param("limit", String.valueOf(limit)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_MACRO_DATA_REQUEST"));
        }
    }

    @Test
    @DisplayName("FRED 不可用时同步接口返回 503")
    void returnsServiceUnavailableWhenFredFails() throws Exception {
        doThrow(new MacroDataUnavailableException(
                "FRED 实际利率服务暂时不可用"
        )).when(syncService).syncDailyObservations();

        mockMvc.perform(post("/api/macro-data/real-rate/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("MACRO_DATA_UNAVAILABLE"));
    }

    private MacroObservation observation(String date, String value) {
        return new MacroObservation(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                SERIES_ID,
                java.time.LocalDate.parse(date),
                new BigDecimal(value),
                "percent",
                "fred",
                COLLECTED_AT,
                null
        );
    }
}
