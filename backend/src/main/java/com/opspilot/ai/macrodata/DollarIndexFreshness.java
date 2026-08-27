package com.opspilot.ai.macrodata;

/** 表示广义美元指数相对当前 UTC 日期的新鲜程度。 */
public enum DollarIndexFreshness {
    /** 最新观测距当前日期不超过七个自然日。 */
    CURRENT,
    /** 最新观测距当前日期已超过七个自然日。 */
    STALE
}
