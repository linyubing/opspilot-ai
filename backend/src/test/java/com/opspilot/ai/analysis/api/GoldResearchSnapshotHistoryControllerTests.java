package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.analysis.ResearchFactorAssessment;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRecordingService;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GoldResearchSnapshotHistoryControllerTests {

    private GoldResearchSnapshotRecordingService recordingService;
    private GoldResearchSnapshotRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recordingService = mock(GoldResearchSnapshotRecordingService.class);
        repository = mock(GoldResearchSnapshotRepository.class);
        mockMvc = standaloneSetup(new GoldResearchSnapshotHistoryController(
                recordingService,
                repository
        )).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("首次正式留痕返回 201")
    void returnsCreatedForNewSnapshot() throws Exception {
        when(recordingService.recordCurrentSnapshot())
                .thenReturn(result(true));

        mockMvc.perform(post("/api/research/gold/snapshots"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.record.snapshot.analysisDate")
                        .value("2026-08-24"))
                .andExpect(jsonPath("$.record.snapshot.researchVersion")
                        .value("gold-multifactor-v2"));
    }

    @Test
    @DisplayName("重复正式留痕返回 200 和已有记录")
    void returnsOkForExistingSnapshot() throws Exception {
        when(recordingService.recordCurrentSnapshot())
                .thenReturn(result(false));

        mockMvc.perform(post("/api/research/gold/snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    @DisplayName("按照请求数量返回最近历史快照")
    void returnsRecentSnapshots() throws Exception {
        when(repository.findRecent(2))
                .thenReturn(List.of(result(true).record()));

        mockMvc.perform(get("/api/research/gold/snapshots")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath(
                        "$[0].snapshot.realRateAssessment.status"
                ).value("NEUTRAL"))
                .andExpect(jsonPath(
                        "$[0].snapshot.dollarIndexAssessment.status"
                ).value("SUPPORTIVE"))
                .andExpect(jsonPath("$[0].snapshot.assessment")
                        .doesNotExist());
    }

    @Test
    @DisplayName("旧单因子历史响应的美元指数字段为 null")
    void returnsNullDollarFieldsForLegacySnapshot() throws Exception {
        when(repository.findRecent(1)).thenReturn(List.of(legacyRecord()));

        mockMvc.perform(get("/api/research/gold/snapshots")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].snapshot.researchVersion")
                        .value("gold-real-rate-v1"))
                .andExpect(jsonPath("$[0].snapshot.dollarIndex")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].snapshot.dollarIndexAssessment")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("历史查询数量超限返回稳定的 400 错误")
    void rejectsInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/research/gold/snapshots")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_RESEARCH_REQUEST"));
    }

    private SaveGoldResearchSnapshotResult result(boolean created) {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-27T01:00:00Z");
        GoldResearchSnapshot snapshot = new GoldResearchSnapshot(
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-25"),
                LocalDate.parse("2026-08-24"),
                new GoldReturnMetrics(
                        new BigDecimal("2500.00"),
                        new BigDecimal("0.1000"),
                        new BigDecimal("1.2000"),
                        new BigDecimal("2.3000"),
                        time
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.380000"),
                        new BigDecimal("-0.020000"),
                        new BigDecimal("-0.060000"),
                        new BigDecimal("-0.060000"),
                        new BigDecimal("-2.00"),
                        new BigDecimal("-6.00"),
                        new BigDecimal("-6.00"),
                        time
                ),
                new DollarIndexChangeMetrics(
                        new BigDecimal("118.062800"),
                        new BigDecimal("-0.1000"),
                        new BigDecimal("-0.6000"),
                        new BigDecimal("-1.2000"),
                        time
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "gold-real-rate-v1",
                        "实际利率变化有限，单因子状态为中性。"
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.SUPPORTIVE,
                        "gold-dollar-index-v1",
                        "广义美元指数走弱，对黄金构成单因子支撑。"
                ),
                "gold-multifactor-v2",
                "双因子状态不构成投资建议。"
        );
        StoredGoldResearchSnapshot record = new StoredGoldResearchSnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                snapshot,
                time
        );
        return new SaveGoldResearchSnapshotResult(record, created);
    }

    /** 构造升级前的单因子记录，验证历史 HTTP 合同兼容性。 */
    private StoredGoldResearchSnapshot legacyRecord() {
        OffsetDateTime time = OffsetDateTime.parse("2026-08-26T01:00:00Z");
        GoldResearchSnapshot snapshot = new GoldResearchSnapshot(
                LocalDate.parse("2026-08-23"),
                LocalDate.parse("2026-08-24"),
                LocalDate.parse("2026-08-23"),
                new GoldReturnMetrics(
                        new BigDecimal("2490.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        time
                ),
                new RealRateChangeMetrics(
                        new BigDecimal("2.400000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        time
                ),
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "gold-real-rate-v1",
                        "旧版实际利率单因子状态为中性。"
                ),
                "旧版单因子状态不构成投资建议。"
        );
        return new StoredGoldResearchSnapshot(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                snapshot,
                time
        );
    }
}
