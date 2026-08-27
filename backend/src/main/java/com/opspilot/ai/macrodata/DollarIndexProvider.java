package com.opspilot.ai.macrodata;

/** 定义广义美元指数每日观测获取契约。 */
public interface DollarIndexProvider {

    DollarIndexBatch fetchDailyObservations();
}
