package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.chat.api.ApiError;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.WalkForwardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供只读的黄金统计模型开发实验结果。 */
@RestController
@RequestMapping("/api/research/gold/model-experiments")
public class ModelExperimentController {

    private static final String HORIZON_ERROR =
            "预测周期只支持 NEXT_DAY、FIVE_DAYS、TWENTY_DAYS";

    private final WalkForwardService service;

    public ModelExperimentController(WalkForwardService service) {
        this.service = service;
    }

    @GetMapping
    public ModelExperimentResponse get(
            @RequestParam(defaultValue = "FIVE_DAYS") String horizon
    ) {
        return ModelExperimentResponse.from(service.run(parse(horizon)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidHorizon(
            IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(
                new ApiError("INVALID_FORECAST_HORIZON", exception.getMessage())
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
