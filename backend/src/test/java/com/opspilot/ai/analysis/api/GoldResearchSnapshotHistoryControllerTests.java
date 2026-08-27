package com.opspilot.ai.analysis.api;

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
                        .value("2026-08-24"));
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
                .andExpect(jsonPath("$[0].snapshot.assessment.status")
                        .value("NEUTRAL"));
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
                new ResearchFactorAssessment(
                        GoldFactorStatus.NEUTRAL,
                        "gold-real-rate-v1",
                        "实际利率变化有限，单因子状态为中性。"
                ),
                "单因子状态不构成投资建议。"
        );
        StoredGoldResearchSnapshot record = new StoredGoldResearchSnapshot(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                snapshot,
                time
        );
        return new SaveGoldResearchSnapshotResult(record, created);
    }
}
