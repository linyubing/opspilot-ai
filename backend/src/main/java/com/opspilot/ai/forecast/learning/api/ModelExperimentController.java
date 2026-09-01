package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.chat.api.ApiError;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ModelExperiment;
import com.opspilot.ai.forecast.learning.ModelExperimentNotFoundException;
import com.opspilot.ai.forecast.learning.ModelExperimentService;
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
            @RequestParam(defaultValue = "FIVE_DAYS") String horizon
    ) {
        return ModelExperimentResponse.from(walkForward.run(parse(horizon)));
    }

    @PostMapping
    public ResponseEntity<ModelExperimentDetailResponse> create(
            @RequestParam(defaultValue = "NEXT_DAY") String horizon
    ) {
        ModelExperiment experiment = experimentService.run(parse(horizon));
        return ResponseEntity.status(201).body(
                ModelExperimentDetailResponse.from(experiment)
        );
    }

    @GetMapping("/history")
    public List<ModelExperimentSummaryResponse> history(
            @RequestParam(defaultValue = "20") int limit
    ) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return experimentService.findRecent(safeLimit).stream()
                .map(ModelExperimentSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ModelExperimentDetailResponse detail(@PathVariable UUID id) {
        ModelExperiment experiment = experimentService.findById(id);
        return ModelExperimentDetailResponse.from(experiment);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidHorizon(
            IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(
                new ApiError("INVALID_FORECAST_HORIZON", exception.getMessage())
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
}
