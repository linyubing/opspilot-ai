package com.opspilot.ai.forecast;

/** 定义黄金方向预测的大模型调用边界。 */
public interface GoldForecastGateway {
    GeneratedGoldForecast generate(GoldForecastPrompt prompt);
}
