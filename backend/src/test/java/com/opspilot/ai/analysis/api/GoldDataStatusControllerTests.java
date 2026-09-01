package com.opspilot.ai.analysis.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证黄金研究数据状态 HTTP 接口。 */
@ExtendWith(MockitoExtension.class)
class GoldDataStatusControllerTests {

    @Mock private GoldDataStatusService dataStatusService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GoldDataStatusController(dataStatusService)
        ).build();
    }

    @Test
    @DisplayName("返回数据状态字段与新鲜状态")
    void returnsDataStatus() throws Exception {
        when(dataStatusService.latest()).thenReturn(new GoldDataStatus(
                List.of(new GoldDataItemStatus(
                        "gold", "黄金价格", LocalDate.parse("2026-08-26"),
                        DataState.FRESH, "处于允许时效内"
                )),
                DataState.FRESH,
                LocalDate.parse("2026-08-27"),
                OffsetDateTime.parse("2026-08-27T00:00:00Z")
        ));

        mockMvc.perform(get("/api/research/gold/data-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("FRESH"))
                .andExpect(jsonPath("$.items[0].code").value("gold"))
                .andExpect(jsonPath("$.items[0].state").value("FRESH"));
    }

    @Test
    @DisplayName("无数据时返回空列表与未知状态")
    void returnsEmptyWhenNoSnapshot() throws Exception {
        when(dataStatusService.latest()).thenReturn(GoldDataStatus.empty());

        mockMvc.perform(get("/api/research/gold/data-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall").value("UNKNOWN"))
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
