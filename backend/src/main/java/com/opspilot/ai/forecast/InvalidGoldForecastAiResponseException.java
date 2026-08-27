package com.opspilot.ai.forecast;

/** 表示模型没有返回合法的结构化黄金方向预测。 */
public class InvalidGoldForecastAiResponseException extends RuntimeException {
    public InvalidGoldForecastAiResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
