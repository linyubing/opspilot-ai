package com.opspilot.ai.forecast.backtest;

/** 表示真实历史数据不足以创建指定规模的回测任务。 */
public class BacktestDataInsufficientException extends RuntimeException {

    public BacktestDataInsufficientException(String message) {
        super(message);
    }
}
