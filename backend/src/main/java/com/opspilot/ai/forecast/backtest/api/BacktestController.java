package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.BacktestEvaluationService;
import com.opspilot.ai.forecast.backtest.BacktestComparisonService;
import com.opspilot.ai.forecast.backtest.BacktestJobService;
import com.opspilot.ai.forecast.backtest.BacktestPromptVersion;
import com.opspilot.ai.forecast.backtest.BacktestService;
import com.opspilot.ai.forecast.backtest.review.BacktestReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** 提供黄金历史回测的创建、运行、进度、明细和评估接口。 */
@RestController
@RequestMapping("/api/research/gold/backtests")
public class BacktestController {

    private final BacktestService service;
    private final BacktestJobService jobs;
    private final BacktestEvaluationService evaluation;
    private final BacktestReviewService review;
    private final BacktestComparisonService comparison;

    public BacktestController(
            BacktestService service,
            BacktestJobService jobs,
            BacktestEvaluationService evaluation,
            BacktestReviewService review,
            BacktestComparisonService comparison
    ) {
        this.service = service;
        this.jobs = jobs;
        this.evaluation = evaluation;
        this.review = review;
        this.comparison = comparison;
    }

    @PostMapping
    public ResponseEntity<BacktestTaskResponse> create(
            @RequestParam(name = "samples", defaultValue = "60") int samples,
            @RequestParam(
                    name = "version",
                    defaultValue = "BASELINE"
            ) BacktestPromptVersion version
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BacktestTaskResponse.from(service.create(samples, version)));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<BacktestTaskResponse> run(
            @PathVariable("id") UUID id
    ) {
        return ResponseEntity.accepted()
                .body(BacktestTaskResponse.from(jobs.start(id)));
    }

    @GetMapping("/{id}")
    public BacktestTaskResponse get(@PathVariable("id") UUID id) {
        return BacktestTaskResponse.from(service.get(id));
    }

    @GetMapping("/{id}/results")
    public List<BacktestCaseResponse> results(
            @PathVariable("id") UUID id,
            @RequestParam(name = "limit", defaultValue = "60") int limit
    ) {
        return service.results(id, limit).stream()
                .map(BacktestCaseResponse::from)
                .toList();
    }

    @GetMapping("/{id}/samples")
    public List<BacktestSampleResponse> samples(
            @PathVariable("id") UUID id
    ) {
        List<java.time.LocalDate> dates = service.samples(id);
        return IntStream.range(0, dates.size())
                .mapToObj(index -> new BacktestSampleResponse(
                        index + 1,
                        dates.get(index)
                ))
                .toList();
    }

    @GetMapping("/{id}/evaluation")
    public BacktestEvaluationResponse evaluation(
            @PathVariable("id") UUID id
    ) {
        return BacktestEvaluationResponse.from(evaluation.evaluate(id));
    }

    @PostMapping("/{id}/review")
    public BacktestReviewResponse review(@PathVariable("id") UUID id) {
        return BacktestReviewResponse.from(review.review(id));
    }

    @GetMapping("/compare")
    public BacktestComparisonResponse compare(
            @RequestParam("baselineId") UUID baselineId,
            @RequestParam("candidateId") UUID candidateId
    ) {
        return BacktestComparisonResponse.from(
                comparison.compare(baselineId, candidateId)
        );
    }
}
