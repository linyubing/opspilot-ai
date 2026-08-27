package com.opspilot.ai.analysis.narrative.api;

import com.opspilot.ai.analysis.narrative.GoldResearchNarrativeService;
import com.opspilot.ai.analysis.narrative.ResearchNarrativeContent;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoldResearchNarrativeControllerTests {

    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "0da5c4c6-81e0-47e8-b016-b9c070830946"
    );

    private GoldResearchNarrativeService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(GoldResearchNarrativeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GoldResearchNarrativeController(service)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsCreatedForNewNarrativeWithoutRawResponse() throws Exception {
        when(service.generate(SNAPSHOT_ID)).thenReturn(
                new SaveResearchNarrativeResult(
                        record("gold-narrative-prompt-v1", 10),
                        true
                )
        );

        mockMvc.perform(post(path()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.record.snapshotId")
                        .value(SNAPSHOT_ID.toString()))
                .andExpect(jsonPath("$.record.modelName").value("glm-4.7"))
                .andExpect(jsonPath("$.record.promptVersion")
                        .value("gold-narrative-prompt-v1"))
                .andExpect(jsonPath("$.record.content.summary").isNotEmpty())
                .andExpect(jsonPath("$.record.rawResponse").doesNotExist());
    }

    @Test
    void returnsOkForExistingNarrative() throws Exception {
        when(service.generate(SNAPSHOT_ID)).thenReturn(
                new SaveResearchNarrativeResult(
                        record("gold-narrative-prompt-v1", 10),
                        false
                )
        );

        mockMvc.perform(post(path()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void returnsHistoryInServiceOrder() throws Exception {
        when(service.findBySnapshotId(SNAPSHOT_ID)).thenReturn(List.of(
                record("v2", 20),
                record("v1", 10)
        ));

        mockMvc.perform(get(path()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].promptVersion").value("v2"))
                .andExpect(jsonPath("$[1].promptVersion").value("v1"))
                .andExpect(jsonPath("$[0].rawResponse").doesNotExist());
    }

    private String path() {
        return "/api/research/gold/snapshots/"
                + SNAPSHOT_ID
                + "/narratives";
    }

    private StoredResearchNarrative record(
            String promptVersion,
            int minute
    ) {
        return new StoredResearchNarrative(
                UUID.randomUUID(),
                SNAPSHOT_ID,
                new ResearchNarrativeContent(
                        "双因子研究摘要",
                        "实际利率分析",
                        "美元指数分析",
                        List.of("日期差异"),
                        List.of("继续观察"),
                        "不构成价格预测、交易或投资建议"
                ),
                "glm-4.7",
                promptVersion,
                "a".repeat(64),
                "不应通过 HTTP 返回的原始响应",
                OffsetDateTime.parse("2026-08-27T12:" + minute + ":00Z")
        );
    }
}
