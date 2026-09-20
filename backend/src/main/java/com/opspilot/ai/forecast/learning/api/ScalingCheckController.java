package com.opspilot.ai.forecast.learning.api;

import com.opspilot.ai.chat.api.ApiError;
import com.opspilot.ai.forecast.learning.ForecastHorizon;
import com.opspilot.ai.forecast.learning.ScalingCheckService;
import com.opspilot.ai.forecast.learning.ScalingReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供训练标准化的配对验证入口，结果不自动替换正式模型。 */
@RestController
@RequestMapping("/api/research/gold/model-experiments/scaling-check")
public class ScalingCheckController {
    private final ScalingCheckService service;

    public ScalingCheckController(ScalingCheckService service) { this.service = service; }

    @PostMapping
    public ScalingReport run(@RequestParam(defaultValue = "NEXT_DAY") ForecastHorizon horizon) {
        return service.run(horizon);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", error.getMessage()));
    }
}
