package com.opspilot.ai.forecast.review;

/** 表示大模型没有返回合法的结构化黄金预测复盘。 */
public class InvalidGoldForecastReviewAiResponseException
        extends RuntimeException {

    public InvalidGoldForecastReviewAiResponseException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
