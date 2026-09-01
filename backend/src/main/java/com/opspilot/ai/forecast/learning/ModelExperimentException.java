package com.opspilot.ai.forecast.learning;

/** 模型实验执行异常。 */
public class ModelExperimentException extends RuntimeException {
    public ModelExperimentException(String message, Throwable cause) {
        super(message, cause);
    }
}
