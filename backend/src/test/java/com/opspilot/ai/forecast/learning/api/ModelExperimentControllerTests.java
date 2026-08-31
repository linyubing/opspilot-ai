package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ForecastMetrics;
import com.opspilot.ai.forecast.learning.WalkForwardReport;
import com.opspilot.ai.forecast.learning.WalkForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelExperimentControllerTests {

    private WalkForwardService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(WalkForwardService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ModelExperimentController(service)
        ).build();
    }

    @Test
    void returnsDevelopmentMetricsWithoutHoldoutAccuracy() throws Exception {
        when(service.run(ForecastHorizon.FIVE_DAYS)).thenReturn(report());

        mvc.perform(get("/api/research/gold/model-experiments")
                        .param("horizon", "FIVE_DAYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizon").value("FIVE_DAYS"))
                .andExpect(jsonPath("$.majority.accuracy").isNumber())
                .andExpect(jsonPath("$.logistic.balancedAccuracy").isNumber())
                .andExpect(jsonPath("$.finalHoldout.samples").value(240))
                .andExpect(jsonPath("$.finalHoldout.accuracy").doesNotExist());
    }

    @Test
    void rejectsInvalidHorizonInChinese() throws Exception {
        mvc.perform(get("/api/research/gold/model-experiments")
                        .param("horizon", "WEEK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "预测周期只支持 NEXT_DAY、FIVE_DAYS、TWENTY_DAYS"
                ));
    }

    private WalkForwardReport report() {
        ForecastMetrics metrics = metrics();
        return new WalkForwardReport(
                ForecastHorizon.FIVE_DAYS,
                LocalDate.parse("2020-01-01"),
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-12-31"),
                240,
                20,
                12,
                metrics,
                metrics,
                240,
                LocalDate.parse("2025-01-10"),
                LocalDate.parse("2025-12-31")
        );
    }

    private ForecastMetrics metrics() {
        Map<ForecastDirection, BigDecimal> recalls = new EnumMap<>(ForecastDirection.class);
        Map<ForecastDirection, Map<ForecastDirection, Integer>> matrix =
                new EnumMap<>(ForecastDirection.class);
        for (ForecastDirection direction : ForecastDirection.values()) {
            recalls.put(direction, new BigDecimal("0.6000"));
            matrix.put(direction, Map.of(direction, 1));
        }
        return new ForecastMetrics(
                240,
                200,
                new BigDecimal("0.8333"),
                new BigDecimal("0.6000"),
                new BigDecimal("0.6000"),
                new BigDecimal("0.4500"),
                recalls,
                matrix,
                true
        );
    }
}
