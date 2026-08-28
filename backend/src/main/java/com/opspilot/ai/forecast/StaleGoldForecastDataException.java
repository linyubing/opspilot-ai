package com.opspilot.ai.forecast;

/** 表示研究快照中的行情或宏观数据过旧，不能用于生成新预测。 */
public class StaleGoldForecastDataException extends RuntimeException {

    public StaleGoldForecastDataException(String message) {
        super(message);
    }
}
