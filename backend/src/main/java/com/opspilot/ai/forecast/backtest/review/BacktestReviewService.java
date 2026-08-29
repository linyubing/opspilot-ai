package com.opspilot.ai.forecast.backtest.review;

import com.opspilot.ai.forecast.backtest.BacktestService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 编排回测结果查询、复盘提示词构建和大模型调用。 */
@Service
public class BacktestReviewService {

    private final BacktestService backtests;
    private final BacktestReviewPromptBuilder builder;
    private final BacktestReviewGateway gateway;

    public BacktestReviewService(
            BacktestService backtests,
            BacktestReviewPromptBuilder builder,
            BacktestReviewGateway gateway
    ) {
        this.backtests = backtests;
        this.builder = builder;
        this.gateway = gateway;
    }

    public GeneratedBacktestReview review(UUID id) {
        var cases = backtests.results(id, 120);
        BacktestReviewPrompt prompt = builder.build(cases);
        return gateway.generate(prompt);
    }
}
