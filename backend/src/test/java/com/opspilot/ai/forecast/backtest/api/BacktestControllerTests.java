package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.backtest.BacktestEvaluation;
import com.opspilot.ai.forecast.backtest.BacktestEvaluationService;
import com.opspilot.ai.forecast.backtest.BacktestJobService;
import com.opspilot.ai.forecast.backtest.BacktestService;
import com.opspilot.ai.forecast.backtest.BacktestStatus;
import com.opspilot.ai.forecast.backtest.BacktestTask;
import com.opspilot.ai.forecast.backtest.ConfusionMatrix;
import com.opspilot.ai.forecast.backtest.DirectionCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证黄金回测 HTTP 接口及敏感字段隔离。 */
class BacktestControllerTests {

    private static final UUID ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private BacktestService service;
    private BacktestJobService jobs;
    private BacktestEvaluationService evaluation;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BacktestService.class);
        jobs = mock(BacktestJobService.class);
        evaluation = mock(BacktestEvaluationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new BacktestController(service, jobs, evaluation)
        ).build();
    }

    @Test
    void createsTask() throws Exception {
        when(service.create(60)).thenReturn(task(BacktestStatus.CREATED));

        mvc.perform(post("/api/research/gold/backtests")
                        .param("samples", "60"))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.sampleCount").value(60))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void startsTaskAsynchronously() throws Exception {
        when(jobs.start(ID)).thenReturn(task(BacktestStatus.RUNNING));

        mvc.perform(post("/api/research/gold/backtests/{id}/run", ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getsTask() throws Exception {
        when(service.get(ID)).thenReturn(task(BacktestStatus.COMPLETED));

        mvc.perform(get("/api/research/gold/backtests/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getsResultsWithoutRawResponse() throws Exception {
        when(service.results(ID, 60)).thenReturn(List.of());

        mvc.perform(get("/api/research/gold/backtests/{id}/results", ID)
                        .param("limit", "60"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("rawResponse")
                )));
    }

    @Test
    void getsFrozenSampleDates() throws Exception {
        when(service.samples(ID)).thenReturn(List.of(
                LocalDate.parse("2012-01-03"),
                LocalDate.parse("2020-06-15"),
                LocalDate.parse("2026-08-19")
        ));

        mvc.perform(get("/api/research/gold/backtests/{id}/samples", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[0].asOfDate").value("2012-01-03"))
                .andExpect(jsonPath("$[2].position").value(3))
                .andExpect(jsonPath("$[2].asOfDate").value("2026-08-19"));
    }

    @Test
    void getsBacktestEvaluation() throws Exception {
        DirectionEvaluation empty = new DirectionEvaluation(
                ForecastDirection.BULLISH, 0, 0, null
        );
        when(evaluation.evaluate(ID)).thenReturn(new BacktestEvaluation(
                "BACKTEST", 21, new BigDecimal("0.5238"),
                new BigDecimal("0.5000"), new BigDecimal("0.2857"),
                new BigDecimal("0.3810"), new BigDecimal("0.1428"),
                new BigDecimal("0.5238"), new ConfusionMatrix(
                        new DirectionCounts(4, 0, 3),
                        new DirectionCounts(3, 3, 0),
                        new DirectionCounts(0, 4, 4)
                ),
                empty, new DirectionEvaluation(
                        ForecastDirection.NEUTRAL, 0, 0, null
                ), new DirectionEvaluation(
                        ForecastDirection.BEARISH, 0, 0, null
                )
        ));

        mvc.perform(get("/api/research/gold/backtests/{id}/evaluation", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BACKTEST"))
                .andExpect(jsonPath("$.accuracy").value(0.5238))
                .andExpect(jsonPath("$.majorityBaselineAccuracy").value(0.3810))
                .andExpect(jsonPath("$.accuracyLift").value(0.1428))
                .andExpect(jsonPath("$.balancedAccuracy").value(0.5238))
                .andExpect(jsonPath("$.confusionMatrix.actualBullish.bullish").value(4))
                .andExpect(jsonPath("$.confusionMatrix.actualNeutral.neutral").value(3))
                .andExpect(jsonPath("$.confusionMatrix.actualBearish.bearish").value(4))
                .andExpect(jsonPath("$.conclusion.level").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.conclusion.summary").value(
                        "有效样本不足 30 条，当前结果只能用于观察。"
                ));
    }

    private BacktestTask task(BacktestStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T08:00:00Z");
        return new BacktestTask(
                ID, LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-08-20"), 60,
                "glm-4.7", "prompt-v1", "rule-v1", status,
                0, 0, 0, null, now, null, null
        );
    }
}
