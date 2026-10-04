package com.opspilot.ai.marketdata.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.assertj.core.api.Assertions.assertThat;
import com.opspilot.ai.chat.api.GlobalExceptionHandler;
import com.opspilot.ai.marketdata.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class GoldSessionCheckControllerTests {
    private GoldSessionCheckService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(GoldSessionCheckService.class);
        mvc = standaloneSetup(new GoldSessionCheckController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void exposesDiagnosticWithoutPrices() throws Exception {
        var date = LocalDate.of(2026, 4, 4);
        when(service.check(date)).thenReturn(new GoldSessionCheckResult(GoldSession.forDate(date),
                Instant.parse("2026-04-05T00:00:00Z"), GoldSessionCheckResult.Status.MISSING_HOURS,
                25, 24, List.of(Instant.parse("2026-04-04T15:00:00Z")), "候选时段缺少小时线"));
        var result = mvc.perform(get("/api/market-data/gold/session-check").param("date", "2026-04-04"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-04-04"))
                .andExpect(jsonPath("$.start").value("2026-04-03T20:00:00Z"))
                .andExpect(jsonPath("$.end").value("2026-04-04T21:00:00Z"))
                .andExpect(jsonPath("$.checkedAt").value("2026-04-05T00:00:00Z"))
                .andExpect(jsonPath("$.status").value("MISSING_HOURS"))
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.ruleVersion").value("sydney-0700-candidate-v1"))
                .andExpect(jsonPath("$.expectedHours").value(25)).andExpect(jsonPath("$.receivedRows").value(24))
                .andExpect(jsonPath("$.missingHours[0]").value("2026-04-04T15:00:00Z"))
                .andExpect(jsonPath("$.warning").value("候选时段核验，不是历史可得性或正式预测晋级证明"))
                .andExpect(jsonPath("$.open").doesNotExist()).andExpect(jsonPath("$.apikey").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("twelvedata.com", "10.00000000");
    }

    @Test
    void rejectsInvalidDate() throws Exception {
        mvc.perform(get("/api/market-data/gold/session-check").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void reportsUpstreamUnavailable() throws Exception {
        when(service.check(LocalDate.of(2026, 4, 4)))
                .thenThrow(new MarketDataUnavailableException("黄金时段数据暂不可用"));
        mvc.perform(get("/api/market-data/gold/session-check").param("date", "2026-04-04"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_DATA_UNAVAILABLE"));
    }
}
