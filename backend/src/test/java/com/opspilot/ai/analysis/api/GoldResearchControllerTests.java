package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
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
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        snapshotService = mock(GoldResearchSnapshotService.class);
        preparationService = mock(GoldResearchPreparationService.class);
        mockMvc = standaloneSetup(new GoldResearchController(
                snapshotService,
                preparationService
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
}
