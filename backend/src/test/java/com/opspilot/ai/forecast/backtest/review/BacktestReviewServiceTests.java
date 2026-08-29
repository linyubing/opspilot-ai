package com.opspilot.ai.forecast.backtest.review;

import com.opspilot.ai.forecast.backtest.BacktestCase;
import com.opspilot.ai.forecast.backtest.BacktestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

        GeneratedBacktestReview actual = new BacktestReviewService(
                backtests,
                builder,
                gateway
        ).review(id);

        assertThat(actual).isEqualTo(expected);
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
                gateway
        );

        assertThatThrownBy(() -> service.review(id))
                .isInstanceOf(NoBacktestErrorsException.class);
        verify(gateway, never()).generate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("相同回测内容重复请求时复用成功结果")
    void cachesSuccess() {
        Fixture fixture = fixture();

        GeneratedBacktestReview first = fixture.service.review(fixture.id);
        GeneratedBacktestReview second = fixture.service.review(fixture.id);

        assertThat(second).isSameAs(first);
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

            assertThat(first.get(2, TimeUnit.SECONDS))
                    .isSameAs(fixture.expected);
            assertThat(second.get(2, TimeUnit.SECONDS))
                    .isSameAs(fixture.expected);
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
        assertThat(fixture.service.review(fixture.id))
                .isSameAs(fixture.expected);
        verify(fixture.gateway, times(2)).generate(fixture.prompt);
    }

    private Fixture fixture() {
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
        when(backtests.results(id, 120)).thenReturn(cases);
        when(builder.build(cases)).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(expected);
        return new Fixture(
                id,
                prompt,
                expected,
                gateway,
                new BacktestReviewService(backtests, builder, gateway)
        );
    }

    private record Fixture(
            UUID id,
            BacktestReviewPrompt prompt,
            GeneratedBacktestReview expected,
            BacktestReviewGateway gateway,
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
