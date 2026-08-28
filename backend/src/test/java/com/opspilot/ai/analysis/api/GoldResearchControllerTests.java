package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldDailyResearchReportResult;
import com.opspilot.ai.analysis.GoldDailyResearchReportService;
import com.opspilot.ai.analysis.GoldDailyResearchReportQueryService;
import com.opspilot.ai.analysis.GoldDailyResearchReportNotFoundException;
import com.opspilot.ai.analysis.StoredGoldDailyResearchReport;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchPreparationResult;
import com.opspilot.ai.analysis.GoldResearchPreparationService;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.analysis.InvalidResearchDataException;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.ResearchNarrativeContent;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.ForecastStatus;
import com.opspilot.ai.forecast.SaveGoldForecastResult;
import com.opspilot.ai.forecast.StaleGoldForecastDataException;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GoldResearchControllerTests {

    private static final String DISCLAIMER =
            "实际利率状态仅代表单一研究因素，"
                    + "不构成黄金方向预测或投资建议。";

    private GoldResearchSnapshotService snapshotService;
    private GoldResearchPreparationService preparationService;
    private GoldDailyResearchReportService dailyReportService;
    private GoldDailyResearchReportQueryService dailyReportQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        snapshotService = mock(GoldResearchSnapshotService.class);
        preparationService = mock(GoldResearchPreparationService.class);
        dailyReportService = mock(GoldDailyResearchReportService.class);
        dailyReportQueryService = mock(GoldDailyResearchReportQueryService.class);
        mockMvc = standaloneSetup(new GoldResearchController(
                snapshotService,
                preparationService,
                dailyReportService,
                dailyReportQueryService
        ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("返回完整的确定性黄金研究快照")
    void returnsResearchSnapshot() throws Exception {
        // 固定数值只验证 HTTP 合同，不代表真实行情。
        when(snapshotService.createSnapshot()).thenReturn(snapshot());

        mockMvc.perform(get("/api/research/gold/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisDate")
                        .value("2026-08-24"))
                .andExpect(jsonPath("$.latestGoldDate")
                        .value("2026-08-25"))
                .andExpect(jsonPath("$.latestRealRateDate")
                        .value("2026-08-24"))
                .andExpect(jsonPath("$.latestDollarIndexDate")
                        .value("2026-08-24"))
                .andExpect(jsonPath("$.gold.return20")
                        .value(2.3456))
                .andExpect(jsonPath("$.realRate.basisPointChange20")
                        .value(18.00))
                .andExpect(jsonPath("$.dollarIndex.currentIndex")
                        .value(118.0628))
                .andExpect(jsonPath("$.realRateAssessment.status")
                        .value("PRESSURING"))
                .andExpect(jsonPath("$.realRateAssessment.ruleVersion")
                        .value("gold-real-rate-v1"))
                .andExpect(jsonPath("$.dollarIndexAssessment.status")
                        .value("SUPPORTIVE"))
                .andExpect(jsonPath("$.researchVersion")
                        .value("gold-multifactor-v2"))
                .andExpect(jsonPath("$.assessment").doesNotExist())
                .andExpect(jsonPath("$.disclaimer")
                        .value(DISCLAIMER));
    }

    @Test
    @DisplayName("每日研究准备接口返回三类同步统计和快照状态")
    void returnsDailyPreparationResult() throws Exception {
        when(preparationService.prepareDaily()).thenReturn(preparationResult(true));

        mockMvc.perform(post("/api/research/gold/daily-preparation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goldPriceSync.savedCount").value(2))
                .andExpect(jsonPath("$.realRateSync.insertedCount").value(3))
                .andExpect(jsonPath("$.dollarIndexSync.insertedCount").value(4))
                .andExpect(jsonPath("$.snapshot.created").value(true));
    }

    @Test
    @DisplayName("重复准备同一研究快照时返回 created=false")
    void returnsExistingSnapshotStatus() throws Exception {
        when(preparationService.prepareDaily()).thenReturn(preparationResult(false));

        mockMvc.perform(post("/api/research/gold/daily-preparation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.created").value(false));
    }

    @Test
    @DisplayName("每日报告接口返回真实数据准备结果和大模型研究解读")
    void returnsDailyResearchReport() throws Exception {
        when(dailyReportService.generateDailyReport())
                .thenReturn(dailyReportResult());

        mockMvc.perform(post("/api/research/gold/daily-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preparation.snapshot.record.id")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.narrative.record.snapshotId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.narrative.created").value(true))
                .andExpect(jsonPath("$.forecast.record.predictedDirection")
                        .value("BULLISH"))
                .andExpect(jsonPath("$.forecast.record.reasoning")
                        .value("美元指数走弱对黄金构成支撑。"))
                .andExpect(jsonPath("$.forecast.created").value(true));
    }

    @Test
    @DisplayName("最新完整报告接口返回同一快照的研究解读和方向预测")
    void returnsLatestCompleteDailyReport() throws Exception {
        when(dailyReportQueryService.findLatestCompleteReport())
                .thenReturn(storedDailyReport());

        mockMvc.perform(get("/api/research/gold/daily-report/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.id")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.narrative.snapshotId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.forecast.snapshotId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.forecast.predictedDirection")
                        .value("BULLISH"));
    }

    @Test
    @DisplayName("最新黄金日报不完整时返回 404 和稳定错误码")
    void returnsNotFoundForIncompleteDailyReport() throws Exception {
        doThrow(new GoldDailyResearchReportNotFoundException("方向预测"))
                .when(dailyReportQueryService)
                .findLatestCompleteReport();

        mockMvc.perform(get("/api/research/gold/daily-report/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("GOLD_DAILY_REPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("预测输入数据过期时返回 422 和明确错误码")
    void returnsUnprocessableEntityForStaleForecastData() throws Exception {
        doThrow(new StaleGoldForecastDataException("美元指数已过期"))
                .when(dailyReportService).generateDailyReport();

        mockMvc.perform(post("/api/research/gold/daily-report"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("FORECAST_DATA_STALE"));
    }

    @Test
    @DisplayName("共同日期不足时返回 422 和明确错误码")
    void returnsUnprocessableEntityForInsufficientData()
            throws Exception {
        doThrow(new InsufficientResearchDataException(
                "共同观测日期不足，实际=20，最低要求=21"
        )).when(snapshotService).createSnapshot();

        mockMvc.perform(get("/api/research/gold/snapshot"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("INSUFFICIENT_RESEARCH_DATA"));
    }

    @Test
    @DisplayName("研究数据非法时返回 422 和明确错误码")
    void returnsUnprocessableEntityForInvalidData()
            throws Exception {
        doThrow(new InvalidResearchDataException(
                "黄金价格必须大于 0"
        )).when(snapshotService).createSnapshot();

        mockMvc.perform(get("/api/research/gold/snapshot"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_RESEARCH_DATA"));
    }

    private GoldResearchSnapshot snapshot() {
        OffsetDateTime collectedAt =
                OffsetDateTime.parse("2026-08-26T01:00:00Z");

        return new GoldResearchSnapshot(
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-25"),
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-24"),
                new GoldReturnMetrics(
                        new BigDecimal("2500.00"),
                        new BigDecimal("0.1000"),
                        new BigDecimal("1.2345"),
                        new BigDecimal("2.3456"),
                        collectedAt
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.38"),
                        new BigDecimal("0.020000"),
                        new BigDecimal("0.080000"),
                        new BigDecimal("0.180000"),
                        new BigDecimal("2.00"),
                        new BigDecimal("8.00"),
                        new BigDecimal("18.00"),
                        collectedAt
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118.0628"),
                        new BigDecimal("-0.1000"),
                        new BigDecimal("-0.6000"),
                        new BigDecimal("-1.2000"),
                        collectedAt
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.PRESSURING,
                        "gold-real-rate-v1",
                        "实际利率构成单因子压力。"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE,
                        "gold-dollar-index-v1",
                        "广义美元指数构成单因子支撑。"
                ),
                "gold-multifactor-v2",
                DISCLAIMER
        );
    }

    private GoldResearchPreparationResult preparationResult(boolean created) {
        OffsetDateTime collectedAt =
                OffsetDateTime.parse("2026-08-27T01:00:00Z");
        StoredGoldResearchSnapshot record = new StoredGoldResearchSnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                snapshot(),
                collectedAt
        );

        return new GoldResearchPreparationResult(
                new GoldPriceSyncResult(
                        3, 2, 1, LocalDate.parse("2026-08-26")
                ),
                new RealRateSyncResult(5, 1, 3, 0, 1, collectedAt),
                new DollarIndexSyncResult(6, 1, 4, 0, 1, collectedAt),
                new SaveGoldResearchSnapshotResult(record, created)
        );
    }

    private GoldDailyResearchReportResult dailyReportResult() {
        UUID snapshotId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
        );
        StoredResearchNarrative narrative = new StoredResearchNarrative(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                snapshotId,
                new ResearchNarrativeContent(
                        "黄金研究摘要",
                        "实际利率因素解读",
                        "美元指数因素解读",
                        List.of("真实数据可能修订"),
                        List.of("关注实际利率变化"),
                        "不构成价格预测、交易或投资建议。"
                ),
                "glm-4.7",
                "gold-research-narrative-v1",
                "prompt-hash",
                "raw-response",
                OffsetDateTime.parse("2026-08-27T01:05:00Z")
        );
        StoredGoldDirectionForecast forecast =
                new StoredGoldDirectionForecast(
                        UUID.fromString(
                                "33333333-3333-3333-3333-333333333333"
                        ),
                        snapshotId,
                        LocalDate.parse("2026-08-26"),
                        new BigDecimal("2500.00"),
                        ForecastDirection.BULLISH,
                        "美元指数走弱对黄金构成支撑。",
                        List.of("美元指数重新转强"),
                        "glm-4.7",
                        "gold-direction-v1",
                        "forecast-prompt-hash",
                        "gold-direction-rule-v1",
                        "raw-forecast-response",
                        ForecastStatus.PENDING,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        OffsetDateTime.parse("2026-08-27T01:06:00Z")
                );

        return new GoldDailyResearchReportResult(
                preparationResult(true),
                new SaveResearchNarrativeResult(narrative, true),
                new SaveGoldForecastResult(forecast, true)
        );
    }

    private StoredGoldDailyResearchReport storedDailyReport() {
        GoldDailyResearchReportResult generated = dailyReportResult();
        return new StoredGoldDailyResearchReport(
                generated.preparation().snapshot().record(),
                generated.narrative().record(),
                generated.forecast().record()
        );
    }
}
