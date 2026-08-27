package com.opspilot.ai.forecast;

/** 表示模型预测违反结构约束或金融安全边界。 */
public class UnsafeGoldForecastException extends RuntimeException {
    public UnsafeGoldForecastException(String message) {
        super(message);
    }
}
