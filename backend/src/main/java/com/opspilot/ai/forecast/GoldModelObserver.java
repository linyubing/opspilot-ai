package com.opspilot.ai.forecast;

/** 按一次正式调用观察模型身份与出站参数；没有观察能力时不能补造依据。 */
public interface GoldModelObserver {
    void start(String model, GoldForecastPrompt prompt);
    GoldModelRun finish(String reportedModel);
    void clear();
}
