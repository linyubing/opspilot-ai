package com.opspilot.ai.forecast.learning;

/** 表示当前运行环境无法使用指定统计模型。 */
public class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
