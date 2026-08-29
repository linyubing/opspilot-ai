package com.opspilot.ai.forecast.backtest.review;

import com.opspilot.ai.forecast.backtest.BacktestCase;
import com.opspilot.ai.forecast.backtest.BacktestService;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证回测 AI 复盘的查询、提示词构建和模型调用顺序。 */
class BacktestReviewServiceTests {

    @Test
    @DisplayName("使用当前回测结果生成结构化复盘")
    void reviewsBacktest() {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        BacktestReviewPromptBuilder builder =
                mock(BacktestReviewPromptBuilder.class);
        BacktestReviewGateway gateway = mock(BacktestReviewGateway.class);
        List<BacktestCase> cases = List.of(mock(BacktestCase.class));
        BacktestReviewPrompt prompt =
                new BacktestReviewPrompt("v1", "真实错误样本", java.util.Set.of());
        GeneratedBacktestReview expected = review();
        when(backtests.results(id, 120)).thenReturn(cases);
        when(builder.build(cases)).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(expected);

        BacktestReviewResult actual = new BacktestReviewService(
                backtests,
                builder,
                gateway,
                new SimpleMeterRegistry()
        ).review(id);

        assertThat(actual.review()).isEqualTo(expected);
        assertThat(actual.cached()).isFalse();
    }

    @Test
    @DisplayName("没有错误样本时不调用大模型")
    void skipsModelWithoutErrors() {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        BacktestReviewPromptBuilder builder =
                mock(BacktestReviewPromptBuilder.class);
        BacktestReviewGateway gateway = mock(BacktestReviewGateway.class);
        List<BacktestCase> cases = List.of();
        when(backtests.results(id, 120)).thenReturn(cases);
        when(builder.build(cases)).thenThrow(
                new NoBacktestErrorsException("没有错误样本")
        );
        BacktestReviewService service = new BacktestReviewService(
                backtests,
                builder,
                gateway,
                new SimpleMeterRegistry()
        );

        assertThatThrownBy(() -> service.review(id))
                .isInstanceOf(NoBacktestErrorsException.class);
        verify(gateway, never()).generate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("相同回测内容重复请求时复用成功结果")
    void cachesSuccess() {
        Fixture fixture = fixture();

        BacktestReviewResult first = fixture.service.review(fixture.id);
        BacktestReviewResult second = fixture.service.review(fixture.id);

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.review()).isSameAs(first.review());
        verify(fixture.gateway, times(1)).generate(fixture.prompt);
    }

    @Test
    @DisplayName("相同回测内容并发请求时只调用一次模型")
    void mergesConcurrentCalls() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fixture.gateway.generate(fixture.prompt)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return fixture.expected;
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> fixture.service.review(fixture.id));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> fixture.service.review(fixture.id));
            release.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).cached()).isFalse();
            assertThat(second.get(2, TimeUnit.SECONDS).cached()).isTrue();
            assertThat(second.get().review()).isSameAs(fixture.expected);
        }
        verify(fixture.gateway, times(1)).generate(fixture.prompt);
    }

    @Test
    @DisplayName("模型失败后不缓存异常并允许重新调用")
    void retriesAfterFailure() {
        Fixture fixture = fixture();
        when(fixture.gateway.generate(fixture.prompt))
                .thenThrow(new BacktestReviewAiUnavailableException(
                        "模型暂时不可用",
                        new IllegalStateException("timeout")
                ))
                .thenReturn(fixture.expected);

        assertThatThrownBy(() -> fixture.service.review(fixture.id))
                .isInstanceOf(BacktestReviewAiUnavailableException.class);
        assertThat(fixture.service.review(fixture.id).review())
                .isSameAs(fixture.expected);
        verify(fixture.gateway, times(2)).generate(fixture.prompt);
        assertThat(fixture.registry.get("opspilot.backtest.review.calls")
                .counter().count()).isEqualTo(2);
        assertThat(fixture.registry.get("opspilot.backtest.review.failures")
                .counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("缓存超过六小时未访问后重新调用模型")
    void expiresIdleCache() {
        AtomicLong nanos = new AtomicLong();
        Fixture fixture = fixture(nanos::get);

        fixture.service.review(fixture.id);
        nanos.addAndGet(java.time.Duration.ofHours(6).plusNanos(1).toNanos());
        fixture.service.review(fixture.id);

        verify(fixture.gateway, times(2)).generate(fixture.prompt);
    }

    private Fixture fixture() {
        return fixture(Ticker.systemTicker());
    }

    private Fixture fixture(Ticker ticker) {
        UUID id = UUID.randomUUID();
        BacktestService backtests = mock(BacktestService.class);
        BacktestReviewPromptBuilder builder =
                mock(BacktestReviewPromptBuilder.class);
        BacktestReviewGateway gateway = mock(BacktestReviewGateway.class);
        List<BacktestCase> cases = List.of(mock(BacktestCase.class));
        BacktestReviewPrompt prompt = new BacktestReviewPrompt(
                "v1",
                "真实错误样本",
                java.util.Set.of("case-1")
        );
        GeneratedBacktestReview expected = review();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(backtests.results(id, 120)).thenReturn(cases);
        when(builder.build(cases)).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(expected);
        return new Fixture(
                id,
                prompt,
                expected,
                gateway,
                registry,
                new BacktestReviewService(
                        backtests, builder, gateway, registry, ticker
                )
        );
    }

    private record Fixture(
            UUID id,
            BacktestReviewPrompt prompt,
            GeneratedBacktestReview expected,
            BacktestReviewGateway gateway,
            SimpleMeterRegistry registry,
            BacktestReviewService service
    ) {
    }

    private GeneratedBacktestReview review() {
        return new GeneratedBacktestReview(
                "glm-4.7",
                "{}",
                new BacktestReviewContent(
                        "错误集中在趋势反转日",
                        List.of("case-1"),
                        List.of(),
                        List.of(new BacktestReviewRisk(
                                "样本有限", List.of("case-1")
                        )),
                        "不构成投资建议"
                )
        );
    }
}
