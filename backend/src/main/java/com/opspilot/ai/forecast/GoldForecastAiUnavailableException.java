package com.opspilot.ai.forecast;

/** 表示黄金预测模型当前无法完成调用。 */
public class GoldForecastAiUnavailableException extends RuntimeException {
    public GoldForecastAiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
