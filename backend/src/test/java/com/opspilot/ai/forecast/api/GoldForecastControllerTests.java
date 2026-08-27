package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.*;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证黄金方向预测 HTTP 状态码、字段安全边界和服务路由。 */
@ExtendWith(MockitoExtension.class)
class GoldForecastControllerTests {
    private static final UUID SNAPSHOT_ID = UUID.fromString("0da5c4c6-81e0-47e8-b016-b9c070830946");
    @Mock private GoldForecastGenerationService generationService;
    @Mock private GoldForecastResolutionService resolutionService;
    @Mock private GoldForecastEvaluationService evaluationService;
    @Mock private GoldSettlementService settlementService;
    @Mock private GoldForecastRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GoldForecastController(
                generationService, resolutionService, evaluationService,
                settlementService, repository
        )).build();
    }

    @Test
    void returnsCreatedForFirstForecastWithoutSensitiveFields() throws Exception {
        when(generationService.generate(SNAPSHOT_ID))
                .thenReturn(new SaveGoldForecastResult(record(), true));

        mockMvc.perform(post("/api/research/gold/snapshots/{id}/forecasts", SNAPSHOT_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.record.snapshotId").value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.record.status").value("PENDING"))
                .andExpect(jsonPath("$.record.rawResponse").doesNotExist())
                .andExpect(jsonPath("$.record.prompt").doesNotExist());
    }

    @Test
    void returnsOkForIdempotentForecast() throws Exception {
        when(generationService.generate(SNAPSHOT_ID))
                .thenReturn(new SaveGoldForecastResult(record(), false));
        mockMvc.perform(post("/api/research/gold/snapshots/{id}/forecasts", SNAPSHOT_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void returnsRecentForecasts() throws Exception {
        when(repository.findRecent(5)).thenReturn(List.of(record()));
        mockMvc.perform(get("/api/research/gold/forecasts").param("limit", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].modelName").value("glm-4.7"));
    }

    @Test
    void resolvesWithoutUsingGenerationService() throws Exception {
        when(resolutionService.resolvePending(100))
                .thenReturn(new ResolveGoldForecastsResult(2, 1, 1));
        mockMvc.perform(post("/api/research/gold/forecasts/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedCount").value(2))
                .andExpect(jsonPath("$.resolvedCount").value(1));
        verifyNoInteractions(generationService);
    }

    @Test
    @DisplayName("每日结算接口返回行情同步和预测结算结果")
    void returnsDailySettlementResult() throws Exception {
        when(settlementService.settleDaily()).thenReturn(new GoldSettlementResult(
                new GoldPriceSyncResult(3, 2, 1, LocalDate.parse("2026-08-27")),
                new ResolveGoldForecastsResult(2, 1, 1)
        ));

        mockMvc.perform(post("/api/research/gold/forecasts/daily-settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceSync.savedCount").value(2))
                .andExpect(jsonPath("$.forecastResolution.resolvedCount").value(1));
    }

    @Test
    void returnsNullAccuracyWhenThereAreNoResolvedSamples() throws Exception {
        DirectionEvaluation bullish = new DirectionEvaluation(ForecastDirection.BULLISH, 0, 0, null);
        DirectionEvaluation neutral = new DirectionEvaluation(ForecastDirection.NEUTRAL, 0, 0, null);
        DirectionEvaluation bearish = new DirectionEvaluation(ForecastDirection.BEARISH, 0, 0, null);
        when(evaluationService.evaluate()).thenReturn(new GoldForecastEvaluation(
                1, 1, 0, null, bullish, neutral, bearish, null, null, List.of()
        ));
        mockMvc.perform(get("/api/research/gold/forecasts/evaluation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCount").value(0))
                .andExpect(jsonPath("$.overallAccuracy").value(org.hamcrest.Matchers.nullValue()));
    }

    private StoredGoldDirectionForecast record() {
        return new StoredGoldDirectionForecast(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), SNAPSHOT_ID,
                LocalDate.parse("2026-08-27"), new BigDecimal("2500.000000"),
                ForecastDirection.NEUTRAL, "依据", List.of("条件"), "glm-4.7",
                GoldForecastPromptBuilder.PROMPT_VERSION, "a".repeat(64),
                GoldForecastRule.RULE_VERSION, "敏感原始响应", ForecastStatus.PENDING,
                null, null, null, null, null, null,
                OffsetDateTime.parse("2026-08-27T01:00:00Z")
        );
    }
}
