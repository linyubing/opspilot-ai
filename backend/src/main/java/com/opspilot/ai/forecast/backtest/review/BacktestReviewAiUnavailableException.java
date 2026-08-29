package com.opspilot.ai.forecast.backtest.review;

/** 表示黄金回测复盘的大模型服务暂时不可用。 */
public class BacktestReviewAiUnavailableException extends RuntimeException {

    public BacktestReviewAiUnavailableException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
