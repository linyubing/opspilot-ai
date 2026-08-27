package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.analysis.InvalidResearchDataException;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GoldResearchControllerTests {

    private static final String DISCLAIMER =
            "实际利率状态仅代表单一研究因素，"
                    + "不构成黄金方向预测或投资建议。";

    private GoldResearchSnapshotService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(GoldResearchSnapshotService.class);
        mockMvc = standaloneSetup(new GoldResearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("返回完整的确定性黄金研究快照")
    void returnsResearchSnapshot() throws Exception {
        // 固定数值只验证 HTTP 合同，不代表真实行情。
        when(service.createSnapshot()).thenReturn(snapshot());

        mockMvc.perform(get("/api/research/gold/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisDate")
                        .value("2026-08-24"))
                .andExpect(jsonPath("$.latestGoldDate")
                        .value("2026-08-25"))
                .andExpect(jsonPath("$.latestRealRateDate")
                        .value("2026-08-24"))
                .andExpect(jsonPath("$.gold.return20")
                        .value(2.3456))
                .andExpect(jsonPath("$.realRate.basisPointChange20")
                        .value(18.00))
                .andExpect(jsonPath("$.assessment.status")
                        .value("PRESSURING"))
                .andExpect(jsonPath("$.assessment.ruleVersion")
                        .value("gold-real-rate-v1"))
                .andExpect(jsonPath("$.disclaimer")
                        .value(DISCLAIMER));
    }

    @Test
    @DisplayName("共同日期不足时返回 422 和明确错误码")
    void returnsUnprocessableEntityForInsufficientData()
            throws Exception {
        doThrow(new InsufficientResearchDataException(
                "共同观测日期不足，实际=20，最低要求=21"
        )).when(service).createSnapshot();

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
        )).when(service).createSnapshot();

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
                new ResearchFactorAssessment(
                        GoldFactorStatus.PRESSURING,
                        "gold-real-rate-v1",
                        "实际利率构成单因子压力。"
                ),
                DISCLAIMER
        );
    }
}
