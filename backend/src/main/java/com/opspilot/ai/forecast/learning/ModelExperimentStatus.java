package com.opspilot.ai.forecast.learning;

/** 模型实验状态枚举。 */
public enum ModelExperimentStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    REJECTED,
    CANDIDATE,
    PROMOTED
}
