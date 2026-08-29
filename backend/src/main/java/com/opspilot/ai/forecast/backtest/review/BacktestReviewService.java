package com.opspilot.ai.forecast.backtest.review;

import com.opspilot.ai.forecast.backtest.BacktestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

/** 编排回测结果查询、复盘提示词构建和大模型调用。 */
@Service
public class BacktestReviewService {

    private final BacktestService backtests;
    private final BacktestReviewPromptBuilder builder;
    private final BacktestReviewGateway gateway;
    private final Counter calls;
    private final Counter hits;
    private final Counter failures;
    private final ConcurrentMap<ReviewKey, CompletableFuture<GeneratedBacktestReview>>
            cache = new ConcurrentHashMap<>();

    public BacktestReviewService(
            BacktestService backtests,
            BacktestReviewPromptBuilder builder,
            BacktestReviewGateway gateway,
            MeterRegistry registry
    ) {
        this.backtests = backtests;
        this.builder = builder;
        this.gateway = gateway;
        this.calls = registry.counter("opspilot.backtest.review.calls");
        this.hits = registry.counter("opspilot.backtest.review.cache.hits");
        this.failures = registry.counter("opspilot.backtest.review.failures");
    }

    public BacktestReviewResult review(UUID id) {
        var cases = backtests.results(id, 120);
        BacktestReviewPrompt prompt = builder.build(cases);
        ReviewKey key = new ReviewKey(id, prompt.version(), prompt.content());
        var current = new CompletableFuture<GeneratedBacktestReview>();
        var existing = cache.putIfAbsent(key, current);

        // 已有请求时共同等待同一个结果，避免并发重复调用模型。
        if (existing != null) {
            hits.increment();
            return new BacktestReviewResult(await(existing), true);
        }

        try {
            calls.increment();
            GeneratedBacktestReview result = gateway.generate(prompt);
            current.complete(result);
            return new BacktestReviewResult(result, false);
        } catch (RuntimeException exception) {
            failures.increment();
            current.completeExceptionally(exception);
            cache.remove(key, current);
            throw exception;
        }
    }

    private GeneratedBacktestReview await(
            CompletableFuture<GeneratedBacktestReview> future
    ) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw exception;
        }
    }

    /** 使用完整提示词区分回测内容，避免数据变化后误用旧结果。 */
    private record ReviewKey(UUID id, String version, String content) {
    }
}
