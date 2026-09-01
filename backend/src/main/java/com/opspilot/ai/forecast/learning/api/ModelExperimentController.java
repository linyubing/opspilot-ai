package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.chat.api.ApiError;
import com.opspilot.ai.forecast.learning.FeatureProfile;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentMetric;
import com.opspilot.ai.forecast.learning.ModelExperimentNotFoundException;
import com.opspilot.ai.forecast.learning.ModelExperimentResult;
import com.opspilot.ai.forecast.learning.ModelExperimentService;
import com.opspilot.ai.forecast.learning.ModelType;
import com.opspilot.ai.forecast.learning.WalkForwardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 提供黄金统计模型实验的即时预览和持久化管理。 */
@RestController
@RequestMapping("/api/research/gold/model-experiments")
public class ModelExperimentController {

    private static final String HORIZON_ERROR =
            "预测周期只支持 NEXT_DAY、FIVE_DAYS、TWENTY_DAYS";
    private static final String PROFILE_ERROR =
            "特征组合只支持 BASE_16、OHLC_20、ALL_36";

    private final WalkForwardService walkForward;
    private final ModelExperimentService experimentService;

    public ModelExperimentController(
            WalkForwardService walkForward,
            ModelExperimentService experimentService
    ) {
        this.walkForward = walkForward;
        this.experimentService = experimentService;
    }

    @GetMapping
    public ModelExperimentResponse get(
            @RequestParam(defaultValue = "FIVE_DAYS") String horizon,
            @RequestParam(defaultValue = "ALL_36") String featureProfile
    ) {
        ForecastHorizon h = parse(horizon);
        FeatureProfile p = parseProfile(featureProfile);
        return ModelExperimentResponse.from(walkForward.run(h, p), p);
    }

    @PostMapping
    public ResponseEntity<ModelExperimentDetailResponse> create(
            @RequestParam(defaultValue = "NEXT_DAY") String horizon,
            @RequestParam(defaultValue = "ALL_36") String featureProfile
    ) {
        ModelExperimentResult result = experimentService.run(
                parse(horizon), parseProfile(featureProfile)
        );
        return ResponseEntity.status(201).body(
                ModelExperimentDetailResponse.from(result)
        );
    }

    @PostMapping("/compare")
    public ResponseEntity<List<ModelExperimentDetailResponse>> compare(
            @RequestParam(defaultValue = "FIVE_DAYS") String horizon
    ) {
        List<ModelExperimentResult> results = experimentService.compare(parse(horizon));
        List<ModelExperimentDetailResponse> responses = results.stream()
                .map(ModelExperimentDetailResponse::from)
                .toList();
        return ResponseEntity.status(201).body(responses);
    }

    @GetMapping("/history")
    public List<ModelExperimentSummaryResponse> history(
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
        }
        List<ModelExperiment> experiments = experimentService.findRecent(limit);
        List<ModelExperimentSummaryResponse> summaries = experiments.stream()
                .map(exp -> {
                    List<ModelExperimentMetric> metrics = experimentService.findMetrics(exp.id());
                    ModelExperimentMetric majority = metrics.stream()
                            .filter(m -> m.modelType() == ModelType.MAJORITY)
                            .findFirst().orElse(null);
                    ModelExperimentMetric logistic = metrics.stream()
                            .filter(m -> m.modelType() == ModelType.LOGISTIC)
                            .findFirst().orElse(null);
                    return ModelExperimentSummaryResponse.from(exp, majority, logistic);
                })
                .toList();

        return ModelExperimentSummaryResponse.fillBase16Improvements(summaries);
    }

    @GetMapping("/{id}")
    public ModelExperimentDetailResponse detail(@PathVariable UUID id) {
        ModelExperiment experiment = experimentService.findById(id);
        List<ModelExperimentMetric> metrics = experimentService.findMetrics(id);
        ModelExperimentMetric majority = metrics.stream()
                .filter(m -> m.modelType() == ModelType.MAJORITY)
                .findFirst().orElse(null);
        ModelExperimentMetric logistic = metrics.stream()
                .filter(m -> m.modelType() == ModelType.LOGISTIC)
                .findFirst().orElse(null);
        return ModelExperimentDetailResponse.from(experiment, majority, logistic);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidInput(
            IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(
                new ApiError("INVALID_INPUT", exception.getMessage())
        );
    }

    @ExceptionHandler(ModelExperimentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            ModelExperimentNotFoundException exception
    ) {
        return ResponseEntity.status(404).body(
                new ApiError("MODEL_EXPERIMENT_NOT_FOUND", exception.getMessage())
        );
    }

    private ForecastHorizon parse(String value) {
        try {
            return ForecastHorizon.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(HORIZON_ERROR);
        }
    }

    private FeatureProfile parseProfile(String value) {
        try {
            return FeatureProfile.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(PROFILE_ERROR);
        }
    }
}
