package com.opspilot.ai.forecast.backtest;

/** 区分提示词开发样本和未参与调整的验证样本。 */
public enum BacktestSampleSet {
    DEFAULT,
    HOLDOUT,
    RECENT
}
