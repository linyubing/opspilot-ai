package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ScalingCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScalingCheckControllerTests {
    private static final String PATH = "/api/research/gold/model-experiments/scaling-check";
    private ScalingCheckService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(ScalingCheckService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ScalingCheckController(service)).build();
    }

    @Test
    void usesNextDayByDefault() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isOk());
        verify(service).run(ForecastHorizon.NEXT_DAY);
    }

    @Test
    void acceptsNamedHorizon() throws Exception {
        mvc.perform(post(PATH).param("horizon", "FIVE_DAYS")).andExpect(status().isOk());
        verify(service).run(ForecastHorizon.FIVE_DAYS);
    }

    @Test
    void rejectsInvalidHorizonBeforeRunning() throws Exception {
        mvc.perform(post(PATH).param("horizon", "UNKNOWN")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void runsFixedWindowsWithoutChoosingParameters() throws Exception {
        mvc.perform(post(PATH + "/windows")).andExpect(status().isOk());
        verify(service).windows();
        verifyNoMoreInteractions(service);
    }
}
