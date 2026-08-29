package com.opspilot.ai.forecast.backtest.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证黄金回测可视化页面能够由 Spring Boot 正常提供。 */
@SpringBootTest
@AutoConfigureMockMvc
class BacktestDashboardTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void opensDashboard() throws Exception {
        mvc.perform(get("/backtest.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.TEXT_HTML
                ))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("id=\"caseList\"")
                ))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("id=\"reviewPanel\"")
                ))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("/backtest.js?v=5")
                ));
    }

    @Test
    void loadsDashboardStyles() throws Exception {
        mvc.perform(get("/backtest.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    @Test
    void loadsDashboardScript() throws Exception {
        mvc.perform(get("/backtest.js"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        "text/javascript"
                ))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("data.cached")
                ));
    }
}
