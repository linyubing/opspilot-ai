package com.opspilot.ai.forecast.learning;

/** 模型实验不存在异常。 */
public class ModelExperimentNotFoundException extends RuntimeException {
    public ModelExperimentNotFoundException(String message) {
        super(message);
    }
}
