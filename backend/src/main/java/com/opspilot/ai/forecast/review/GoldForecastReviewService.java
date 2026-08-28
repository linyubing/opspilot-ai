package com.opspilot.ai.forecast.review;

import com.opspilot.ai.forecast.GoldForecastEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluationService;
import org.springframework.stereotype.Service;

/** 编排历史评测、复盘提示词构建和大模型调用。 */
@Service
public class GoldForecastReviewService {

    private final GoldForecastEvaluationService evalService;
    private final GoldForecastReviewPromptBuilder promptBuilder;
    private final GoldForecastReviewGateway gateway;

    public GoldForecastReviewService(
            GoldForecastEvaluationService evalService,
            GoldForecastReviewPromptBuilder promptBuilder,
            GoldForecastReviewGateway gateway
    ) {
        this.evalService = evalService;
        this.promptBuilder = promptBuilder;
        this.gateway = gateway;
    }

    public GeneratedGoldForecastReview review() {
        GoldForecastEvaluation eval = evalService.evaluate();
        GoldForecastReviewPrompt prompt = promptBuilder.build(eval);
        return gateway.generate(prompt);
    }
}
