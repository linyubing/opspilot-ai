package com.opspilot.ai.forecast.backtest;

/** 标识历史回测使用的黄金价格口径。 */
public enum BacktestPriceBasis {
    /** 旧版参考价，仅用于兼容已有回测记录。 */
    LEGACY_REFERENCE,

    /** 真实黄金日线的收盘价。 */
    OHLC_CLOSE
}
