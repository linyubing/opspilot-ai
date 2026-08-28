package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.GoldForecastEvaluationService;
import com.opspilot.ai.forecast.GoldForecastGenerationService;
import com.opspilot.ai.forecast.GoldForecastRepository;
import com.opspilot.ai.forecast.GoldForecastResolutionService;
import com.opspilot.ai.forecast.GoldSettlementService;
import com.opspilot.ai.forecast.SaveGoldForecastResult;
import com.opspilot.ai.forecast.review.GoldForecastReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 提供黄金方向预测生成、历史、结算和评测 HTTP 接口。 */
@RestController
@RequestMapping("/api/research/gold")
public class GoldForecastController {
    private final GoldForecastGenerationService generationService;
    private final GoldForecastResolutionService resolutionService;
    private final GoldForecastEvaluationService evaluationService;
    private final GoldSettlementService settlementService;
    private final GoldForecastRepository repository;
    private final GoldForecastReviewService reviewService;

    public GoldForecastController(
            GoldForecastGenerationService generationService,
            GoldForecastResolutionService resolutionService,
            GoldForecastEvaluationService evaluationService,
            GoldSettlementService settlementService,
            GoldForecastRepository repository,
            GoldForecastReviewService reviewService
    ) {
        this.generationService = generationService;
        this.resolutionService = resolutionService;
        this.evaluationService = evaluationService;
        this.settlementService = settlementService;
        this.repository = repository;
        this.reviewService = reviewService;
    }

    @PostMapping("/snapshots/{snapshotId}/forecasts")
    public ResponseEntity<SaveGoldForecastResponse> generate(@PathVariable UUID snapshotId) {
        SaveGoldForecastResult result = generationService.generate(snapshotId);
        SaveGoldForecastResponse response = SaveGoldForecastResponse.from(result);
        return result.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(response)
                : ResponseEntity.ok(response);
    }

    @GetMapping("/forecasts")
    public List<GoldForecastResponse> history(@RequestParam(defaultValue = "20") int limit) {
        return repository.findRecent(limit).stream().map(GoldForecastResponse::from).toList();
    }

    @PostMapping("/forecasts/resolve")
    public ResolveGoldForecastsResponse resolve(@RequestParam(defaultValue = "100") int limit) {
        return ResolveGoldForecastsResponse.from(resolutionService.resolvePending(limit));
    }

    @PostMapping("/forecasts/daily-settlement")
    public GoldSettlementResponse settleDaily() {
        return GoldSettlementResponse.from(settlementService.settleDaily());
    }

    @GetMapping("/forecasts/evaluation")
    public GoldForecastEvaluationResponse evaluation() {
        return GoldForecastEvaluationResponse.from(evaluationService.evaluate());
    }

    @PostMapping("/forecasts/review")
    public GoldForecastReviewResponse review() {
        return GoldForecastReviewResponse.from(reviewService.review());
    }
}
