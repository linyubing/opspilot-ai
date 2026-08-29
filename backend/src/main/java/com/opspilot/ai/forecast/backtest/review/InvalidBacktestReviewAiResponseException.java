package com.opspilot.ai.forecast.backtest.review;

/** 表示大模型没有遵守黄金回测复盘的 JSON 输出合同。 */
public class InvalidBacktestReviewAiResponseException extends RuntimeException {

    public InvalidBacktestReviewAiResponseException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
