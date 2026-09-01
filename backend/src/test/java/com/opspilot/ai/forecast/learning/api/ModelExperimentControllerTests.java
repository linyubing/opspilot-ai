package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.learning.FeatureProfile;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ForecastMetrics;
import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;
import com.opspilot.ai.forecast.learning.ModelExperimentNotFoundException;
import com.opspilot.ai.forecast.learning.ModelExperimentResult;
import com.opspilot.ai.forecast.learning.ModelExperimentService;
import com.opspilot.ai.forecast.learning.ModelExperimentStatus;
import com.opspilot.ai.forecast.learning.ModelType;
import com.opspilot.ai.forecast.learning.WalkForwardReport;
import com.opspilot.ai.forecast.learning.WalkForwardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelExperimentControllerTests {

    private WalkForwardService service;
    private ModelExperimentService experimentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(WalkForwardService.class);
        experimentService = mock(ModelExperimentService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ModelExperimentController(service, experimentService)
        ).build();
    }

    @Test
    void oldGetReturnsCompatibleJsonStructure() throws Exception {
        when(service.run(ForecastHorizon.FIVE_DAYS, FeatureProfile.ALL_36)).thenReturn(report());

        mvc.perform(get("/api/research/gold/model-experiments")
                        .param("horizon", "FIVE_DAYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizon").value("FIVE_DAYS"))
                .andExpect(jsonPath("$.featureProfile").value("ALL_36"))
                .andExpect(jsonPath("$.majority.sampleCount").value(240))
                .andExpect(jsonPath("$.majority.coveredCount").value(200))
                .andExpect(jsonPath("$.majority.accuracy").isNumber())
                .andExpect(jsonPath("$.majority.balancedAccuracy").isNumber())
                .andExpect(jsonPath("$.majority.coverage").isNumber())
                .andExpect(jsonPath("$.majority.brierScore").isNumber())
                .andExpect(jsonPath("$.majority.logLoss").isNumber())
                .andExpect(jsonPath("$.majority.recalls.BULLISH").isNumber())
                .andExpect(jsonPath("$.majority.promotionReady").value(true))
                .andExpect(jsonPath("$.logistic.sampleCount").value(240))
                .andExpect(jsonPath("$.finalHoldout.samples").value(240))
                .andExpect(jsonPath("$.finalHoldout.start").isArray())
                .andExpect(jsonPath("$.finalHoldout.end").isArray());
    }

    @Test
    void getWithBase16ProfileRunsBase16() throws Exception {
        when(service.run(ForecastHorizon.FIVE_DAYS, FeatureProfile.BASE_16)).thenReturn(report());

        mvc.perform(get("/api/research/gold/model-experiments")
                        .param("horizon", "FIVE_DAYS")
                        .param("featureProfile", "BASE_16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureProfile").value("BASE_16"));
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

    @Test
    void rejectsInvalidProfile() throws Exception {
        mvc.perform(get("/api/research/gold/model-experiments")
                        .param("featureProfile", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "特征组合只支持 BASE_16、OHLC_20、ALL_36"
                ));
    }

    @Test
    void postReturns201WithMetrics() throws Exception {
        ModelExperiment experiment = createExperiment();
        ModelExperimentMetric majorityMetric = createMetric(ModelType.MAJORITY);
        ModelExperimentMetric logisticMetric = createMetric(ModelType.LOGISTIC);
        ModelExperimentResult result = new ModelExperimentResult(experiment, majorityMetric, logisticMetric);

        when(experimentService.run(ForecastHorizon.NEXT_DAY,
                FeatureProfile.ALL_36)).thenReturn(result);

        mvc.perform(post("/api/research/gold/model-experiments")
                        .param("horizon", "NEXT_DAY"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.featureProfile").value("ALL_36"))
                .andExpect(jsonPath("$.majority.sampleCount").value(240))
                .andExpect(jsonPath("$.majority.accuracy").value(0.6000))
                .andExpect(jsonPath("$.logistic.sampleCount").value(240))
                .andExpect(jsonPath("$.logistic.accuracy").value(0.6000));
    }

    @Test
    void compareReturns201WithComparisonIdAndThreeExperiments() throws Exception {
        UUID comparisonId = UUID.randomUUID();
        ModelExperiment exp1 = createExperimentWithProfile(FeatureProfile.BASE_16, comparisonId);
        ModelExperiment exp2 = createExperimentWithProfile(FeatureProfile.OHLC_20, comparisonId);
        ModelExperiment exp3 = createExperimentWithProfile(FeatureProfile.ALL_36, comparisonId);
        ModelExperimentMetric majorityMetric = createMetric(ModelType.MAJORITY);
        ModelExperimentMetric logisticMetric = createMetric(ModelType.LOGISTIC);

        when(experimentService.compare(ForecastHorizon.FIVE_DAYS)).thenReturn(List.of(
                new ModelExperimentResult(exp1, majorityMetric, logisticMetric),
                new ModelExperimentResult(exp2, majorityMetric, logisticMetric),
                new ModelExperimentResult(exp3, majorityMetric, logisticMetric)
        ));

        mvc.perform(post("/api/research/gold/model-experiments/compare")
                        .param("horizon", "FIVE_DAYS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comparisonId").value(comparisonId.toString()))
                .andExpect(jsonPath("$.horizon").value("NEXT_DAY"))
                .andExpect(jsonPath("$.experiments").isArray())
                .andExpect(jsonPath("$.experiments.length()").value(3))
                .andExpect(jsonPath("$.experiments[0].featureProfile").value("BASE_16"))
                .andExpect(jsonPath("$.experiments[0].comparisonId").value(comparisonId.toString()))
                .andExpect(jsonPath("$.experiments[1].featureProfile").value("OHLC_20"))
                .andExpect(jsonPath("$.experiments[1].comparisonId").value(comparisonId.toString()))
                .andExpect(jsonPath("$.experiments[2].featureProfile").value("ALL_36"))
                .andExpect(jsonPath("$.experiments[2].comparisonId").value(comparisonId.toString()));
    }

    @Test
    void detailReturnsMetricsFromDatabase() throws Exception {
        UUID id = UUID.randomUUID();
        ModelExperiment experiment = createExperiment();
        ModelExperimentMetric majorityMetric = createMetric(ModelType.MAJORITY);
        ModelExperimentMetric logisticMetric = createMetric(ModelType.LOGISTIC);

        when(experimentService.findById(id)).thenReturn(experiment);
        when(experimentService.findMetrics(id)).thenReturn(List.of(majorityMetric, logisticMetric));

        mvc.perform(get("/api/research/gold/model-experiments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.featureProfile").value("ALL_36"))
                .andExpect(jsonPath("$.majority.sampleCount").value(240))
                .andExpect(jsonPath("$.majority.accuracy").isNumber())
                .andExpect(jsonPath("$.logistic.sampleCount").value(240))
                .andExpect(jsonPath("$.logistic.accuracy").isNumber());
    }

    @Test
    void historyReturnsAccuracy() throws Exception {
        ModelExperiment experiment = createExperiment();
        ModelExperimentMetric majorityMetric = createMetric(ModelType.MAJORITY);
        ModelExperimentMetric logisticMetric = createMetric(ModelType.LOGISTIC);

        when(experimentService.findRecent(10)).thenReturn(List.of(experiment));
        when(experimentService.findMetrics(experiment.id()))
                .thenReturn(List.of(majorityMetric, logisticMetric));

        mvc.perform(get("/api/research/gold/model-experiments/history")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(experiment.id().toString()))
                .andExpect(jsonPath("$[0].featureProfile").value("ALL_36"))
                .andExpect(jsonPath("$[0].majorityAccuracy").value(0.6000))
                .andExpect(jsonPath("$[0].logisticAccuracy").value(0.6000));
    }

    @Test
    void rejectsInvalidLimit() throws Exception {
        mvc.perform(get("/api/research/gold/model-experiments/history")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/research/gold/model-experiments/history")
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404ForNonExistentExperiment() throws Exception {
        UUID id = UUID.randomUUID();
        when(experimentService.findById(id))
                .thenThrow(new ModelExperimentNotFoundException("模型实验不存在，编号=" + id));

        mvc.perform(get("/api/research/gold/model-experiments/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODEL_EXPERIMENT_NOT_FOUND"));
    }

    @Test
    void nonCompletedExperimentAllowsNullMetrics() throws Exception {
        UUID id = UUID.randomUUID();
        ModelExperiment experiment = new ModelExperiment(
                id, null, "NEXT_DAY", "v1", "v1", "v1", "hash",
                FeatureProfile.ALL_36, Map.of(), LocalDate.MIN, LocalDate.MAX,
                LocalDate.MIN, LocalDate.MIN, LocalDate.MAX,
                LocalDate.MIN, LocalDate.MAX, 0, 0,
                ModelExperimentStatus.RUNNING, "unknown", null,
                OffsetDateTime.now(ZoneOffset.UTC), null, null
        );

        when(experimentService.findById(id)).thenReturn(experiment);
        when(experimentService.findMetrics(id)).thenReturn(List.of());

        mvc.perform(get("/api/research/gold/model-experiments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.majority").doesNotExist())
                .andExpect(jsonPath("$.logistic").doesNotExist());
    }

    private ModelExperiment createExperiment() {
        return createExperimentWithProfile(FeatureProfile.ALL_36, null);
    }

    private ModelExperiment createExperimentWithProfile(FeatureProfile profile, UUID comparisonId) {
        UUID id = UUID.randomUUID();
        return new ModelExperiment(
                id, comparisonId, "NEXT_DAY", "gold-features-v2", "gold-label-v1", "gold-temporal-split-v1",
                "abc123def456", profile, Map.of("horizon", "NEXT_DAY"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31),
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                LocalDate.of(2025, 1, 10), LocalDate.of(2025, 12, 31), 240, 240,
                ModelExperimentStatus.COMPLETED, "unknown", null,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private ModelExperimentMetric createMetric(ModelType modelType) {
        Map<String, Object> recalls = new java.util.LinkedHashMap<>();
        recalls.put("BULLISH", new BigDecimal("0.6"));
        recalls.put("NEUTRAL", new BigDecimal("0.5"));
        recalls.put("BEARISH", new BigDecimal("0.7"));

        Map<String, Map<String, Integer>> confusionMatrix = new java.util.LinkedHashMap<>();
        confusionMatrix.put("BULLISH", Map.of("BULLISH", 100, "NEUTRAL", 20, "BEARISH", 10));
        confusionMatrix.put("NEUTRAL", Map.of("BULLISH", 15, "NEUTRAL", 80, "BEARISH", 15));
        confusionMatrix.put("BEARISH", Map.of("BULLISH", 10, "NEUTRAL", 15, "BEARISH", 115));

        return new ModelExperimentMetric(
                UUID.randomUUID(), modelType, 240, 200,
                new BigDecimal("0.8333"), new BigDecimal("0.6000"),
                new BigDecimal("0.6000"), new BigDecimal("0.4500"),
                new BigDecimal("1.2000"), recalls, confusionMatrix
        );
    }

    private WalkForwardReport report() {
        ForecastMetrics majority = metrics();
        ForecastMetrics logistic = metrics();
        return new WalkForwardReport(
                ForecastHorizon.FIVE_DAYS,
                LocalDate.parse("2020-01-01"),
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-12-31"),
                240, 20, 12, majority, logistic,
                240, LocalDate.parse("2025-01-10"), LocalDate.parse("2025-12-31")
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
                240, 200, new BigDecimal("0.8333"), new BigDecimal("0.6000"),
                new BigDecimal("0.6000"), new BigDecimal("0.4500"), new BigDecimal("1.2000"),
                recalls, matrix, true
        );
    }
}
