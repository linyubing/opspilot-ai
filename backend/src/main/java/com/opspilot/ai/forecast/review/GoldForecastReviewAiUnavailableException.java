package com.opspilot.ai.forecast.review;

/** 表示黄金预测复盘所依赖的大模型暂时不可用。 */
public class GoldForecastReviewAiUnavailableException
        extends RuntimeException {

    public GoldForecastReviewAiUnavailableException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
