package com.opspilot.ai.forecast.review;

import com.opspilot.ai.forecast.GoldForecastEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证黄金预测复盘按评测、提示词和模型调用顺序完成编排。 */
class GoldForecastReviewServiceTests {

    @Test
    void reviewsEvaluation() {
        GoldForecastEvaluationService evalService =
                mock(GoldForecastEvaluationService.class);
        GoldForecastReviewPromptBuilder builder =
                mock(GoldForecastReviewPromptBuilder.class);
        GoldForecastReviewGateway gateway =
                mock(GoldForecastReviewGateway.class);
        GoldForecastEvaluation eval = mock(GoldForecastEvaluation.class);
        GoldForecastReviewPrompt prompt =
                new GoldForecastReviewPrompt("v1", "评测提示词");
        GeneratedGoldForecastReview expected =
                mock(GeneratedGoldForecastReview.class);
        when(evalService.evaluate()).thenReturn(eval);
        when(builder.build(eval)).thenReturn(prompt);
        when(gateway.generate(prompt)).thenReturn(expected);
        GoldForecastReviewService service = new GoldForecastReviewService(
                evalService,
                builder,
                gateway
        );

        GeneratedGoldForecastReview result = service.review();

        assertThat(result).isSameAs(expected);
    }
}
