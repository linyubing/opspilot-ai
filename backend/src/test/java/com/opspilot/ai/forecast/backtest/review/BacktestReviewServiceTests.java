package com.opspilot.ai.forecast.backtest.review;

import com.opspilot.ai.forecast.backtest.BacktestCase;
import com.opspilot.ai.forecast.backtest.BacktestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
