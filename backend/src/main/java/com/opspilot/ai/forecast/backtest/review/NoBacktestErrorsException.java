package com.opspilot.ai.forecast.backtest.review;

/** 表示当前回测没有可供复盘的错误样本。 */
public class NoBacktestErrorsException extends RuntimeException {

    public NoBacktestErrorsException(String message) {
        super(message);
    }
}
