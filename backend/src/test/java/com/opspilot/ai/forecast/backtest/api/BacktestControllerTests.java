package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.backtest.BacktestEvaluation;
import com.opspilot.ai.forecast.backtest.BacktestComparison;
import com.opspilot.ai.forecast.backtest.BacktestComparisonService;
import com.opspilot.ai.forecast.backtest.BacktestEvaluationService;
import com.opspilot.ai.forecast.backtest.BacktestJobService;
import com.opspilot.ai.forecast.backtest.BacktestPromptVersion;
import com.opspilot.ai.forecast.backtest.BacktestPriceBasis;
import com.opspilot.ai.forecast.backtest.BacktestSampleSet;
import com.opspilot.ai.forecast.backtest.BacktestService;
import com.opspilot.ai.forecast.backtest.BacktestStatus;
import com.opspilot.ai.forecast.backtest.BacktestTask;
import com.opspilot.ai.forecast.backtest.ConfusionMatrix;
import com.opspilot.ai.forecast.backtest.DirectionCounts;
import com.opspilot.ai.forecast.backtest.FactorDiagnostic;
import com.opspilot.ai.forecast.backtest.FactorDiagnosticReport;
import com.opspilot.ai.forecast.backtest.FactorDiagnosticService;
import com.opspilot.ai.forecast.backtest.HorizonDiagnostic;
import com.opspilot.ai.forecast.backtest.HorizonDiagnosticReport;
import com.opspilot.ai.forecast.backtest.HorizonDiagnosticService;
import com.opspilot.ai.forecast.backtest.HistoricalHorizonDiagnosticService;
import com.opspilot.ai.forecast.backtest.HistoricalHorizonReport;
import com.opspilot.ai.forecast.backtest.review.BacktestErrorPattern;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewContent;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewService;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewRisk;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewResult;
import com.opspilot.ai.forecast.backtest.review.GeneratedBacktestReview;
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
    private BacktestReviewService review;
    private BacktestComparisonService comparison;
    private FactorDiagnosticService diagnostics;
    private HorizonDiagnosticService horizons;
    private HistoricalHorizonDiagnosticService historyHorizons;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BacktestService.class);
        jobs = mock(BacktestJobService.class);
        evaluation = mock(BacktestEvaluationService.class);
        review = mock(BacktestReviewService.class);
        comparison = mock(BacktestComparisonService.class);
        diagnostics = mock(FactorDiagnosticService.class);
        horizons = mock(HorizonDiagnosticService.class);
        historyHorizons = mock(HistoricalHorizonDiagnosticService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new BacktestController(
                        service, jobs, evaluation, review, comparison,
                        diagnostics, horizons, historyHorizons
                )
        ).build();
    }

    @Test
    void createsTask() throws Exception {
        when(service.create(
                60,
                BacktestPromptVersion.BASELINE,
                BacktestSampleSet.DEFAULT
        ))
                .thenReturn(task(BacktestStatus.CREATED));

        mvc.perform(post("/api/research/gold/backtests")
                        .param("samples", "60"))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.sampleCount").value(60))
                .andExpect(jsonPath("$.priceBasis").value("OHLC_CLOSE"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void createsCandidateTask() throws Exception {
        when(service.create(
                60,
                BacktestPromptVersion.CANDIDATE,
                BacktestSampleSet.DEFAULT
        ))
                .thenReturn(task(BacktestStatus.CREATED));

        mvc.perform(post("/api/research/gold/backtests")
                        .param("samples", "60")
                        .param("version", "CANDIDATE"))
                .andExpect(status().isCreated());
    }

    @Test
    void createsHoldoutTask() throws Exception {
        when(service.create(
                20,
                BacktestPromptVersion.BASELINE,
                BacktestSampleSet.HOLDOUT
        )).thenReturn(task(BacktestStatus.CREATED));

        mvc.perform(post("/api/research/gold/backtests")
                        .param("samples", "20")
                        .param("version", "BASELINE")
                        .param("sampleSet", "HOLDOUT"))
                .andExpect(status().isCreated());
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

    @Test
    void generatesReviewWithoutRawResponse() throws Exception {
        when(review.review(ID)).thenReturn(new BacktestReviewResult(
                new GeneratedBacktestReview(
                "glm-4.7",
                "敏感原始模型响应",
                new BacktestReviewContent(
                        "错误集中在趋势反转日",
                        List.of("case-1"),
                        List.of(new BacktestErrorPattern(
                                "趋势延续误判",
                                "趋势反转后仍然看涨",
                                List.of("case-1"),
                                "增加趋势衰减条件",
                                "使用下一批历史样本验证"
                        )),
                        List.of(new BacktestReviewRisk(
                                "样本有限", List.of("case-1")
                        )),
                        "不构成投资建议"
                )),
                true
        ));

        mvc.perform(post("/api/research/gold/backtests/{id}/review", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("glm-4.7"))
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.summary").value("错误集中在趋势反转日"))
                .andExpect(jsonPath("$.summaryEvidence[0]").value("case-1"))
                .andExpect(jsonPath("$.patterns[0].category")
                        .value("趋势延续误判"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("rawResponse")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("敏感原始模型响应")
                )));
    }

    @Test
    void comparesPromptVersions() throws Exception {
        UUID candidateId = UUID.randomUUID();
        BacktestEvaluation baseline = evaluation("0.4000", "0.4500");
        BacktestEvaluation candidate = evaluation("0.5500", "0.5200");
        when(comparison.compare(ID, candidateId)).thenReturn(
                new BacktestComparison(
                        ID, candidateId, 60, baseline, candidate,
                        new BigDecimal("0.1500"), new BigDecimal("0.0700")
                )
        );

        mvc.perform(get("/api/research/gold/backtests/compare")
                        .param("baselineId", ID.toString())
                        .param("candidateId", candidateId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(60))
                .andExpect(jsonPath("$.accuracyChange").value(0.1500))
                .andExpect(jsonPath("$.balancedAccuracyChange").value(0.0700));
    }

    @Test
    void getsFactorDiagnostics() throws Exception {
        when(diagnostics.diagnose(ID)).thenReturn(new FactorDiagnosticReport(
                ID,
                20,
                List.of(new FactorDiagnostic(
                        "REAL_RATE", 20, 12, new BigDecimal("0.6000"),
                        8, new BigDecimal("0.4000"), 6,
                        new BigDecimal("0.5000"),
                        new DirectionCounts(5, 8, 7)
                ))
        ));

        mvc.perform(get("/api/research/gold/backtests/{id}/factors", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(20))
                .andExpect(jsonPath("$.factors[0].factor").value("REAL_RATE"))
                .andExpect(jsonPath("$.factors[0].coverage").value(0.6000))
                .andExpect(jsonPath("$.factors[0].directionalAccuracy")
                        .value(0.5000));
    }

    @Test
    void getsHorizonDiagnostics() throws Exception {
        when(horizons.diagnose(ID)).thenReturn(new HorizonDiagnosticReport(
                ID,
                List.of(new HorizonDiagnostic(
                        5,
                        20,
                        List.of(new FactorDiagnostic(
                                "REAL_RATE", 20, 12,
                                new BigDecimal("0.6000"), 10,
                                new BigDecimal("0.5000"), 7,
                                new BigDecimal("0.5833"),
                                new DirectionCounts(5, 8, 7)
                        ))
                ))
        ));

        mvc.perform(get("/api/research/gold/backtests/{id}/horizons", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizons[0].sessions").value(5))
                .andExpect(jsonPath("$.horizons[0].sampleCount").value(20))
                .andExpect(jsonPath(
                        "$.horizons[0].factors[0].directionalAccuracy"
                ).value(0.5833));
    }

    @Test
    void getsExpandedHorizonDiagnostics() throws Exception {
        when(historyHorizons.diagnose(120)).thenReturn(
                new HistoricalHorizonReport(
                        120,
                        List.of(new HorizonDiagnostic(20, 93, List.of()))
                )
        );

        mvc.perform(get("/api/research/gold/backtests/horizons/history")
                        .param("samples", "120"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedSamples").value(120))
                .andExpect(jsonPath("$.horizons[0].sessions").value(20))
                .andExpect(jsonPath("$.horizons[0].sampleCount").value(93));
    }

    private BacktestEvaluation evaluation(String accuracy, String balanced) {
        DirectionEvaluation empty = new DirectionEvaluation(
                ForecastDirection.BULLISH, 0, 0, null
        );
        return new BacktestEvaluation(
                "BACKTEST", 60, new BigDecimal(accuracy), null,
                null, null, null, new BigDecimal(balanced),
                new ConfusionMatrix(
                        new DirectionCounts(0, 0, 0),
                        new DirectionCounts(0, 0, 0),
                        new DirectionCounts(0, 0, 0)
                ),
                empty, empty, empty
        );
    }

    private BacktestTask task(BacktestStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T08:00:00Z");
        return new BacktestTask(
                ID, LocalDate.parse("2026-06-01"),
                LocalDate.parse("2026-08-20"), 60,
                "glm-4.7", "prompt-v1", "rule-v1",
                BacktestPriceBasis.OHLC_CLOSE, status,
                0, 0, 0, null, now, null, null
        );
    }
}
