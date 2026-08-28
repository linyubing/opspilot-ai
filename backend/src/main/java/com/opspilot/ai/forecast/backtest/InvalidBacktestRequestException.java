package com.opspilot.ai.forecast.backtest;

/** 表示黄金回测请求参数不符合允许范围。 */
public class InvalidBacktestRequestException extends RuntimeException {

    public InvalidBacktestRequestException(String message) {
        super(message);
    }
}
