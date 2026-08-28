package com.opspilot.ai.forecast.backtest;

/** 表示指定的黄金回测任务不存在。 */
public class BacktestNotFoundException extends RuntimeException {

    public BacktestNotFoundException(String message) {
        super(message);
    }
}
