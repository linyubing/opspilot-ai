package com.opspilot.ai.forecast.backtest;

/** 表示历史回测结论的可信程度和主要问题。 */
public enum ConclusionLevel {
    INSUFFICIENT,
    NO_EDGE,
    UNBALANCED,
    WEAK,
    PROMISING
}
