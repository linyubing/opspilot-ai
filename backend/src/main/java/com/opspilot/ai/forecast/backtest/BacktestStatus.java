package com.opspilot.ai.forecast.backtest;

/** 表示黄金回测任务的生命周期状态。 */
public enum BacktestStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED
}
