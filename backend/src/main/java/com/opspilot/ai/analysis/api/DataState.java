package com.opspilot.ai.analysis.api;

/** 表示单个黄金研究数据项的新鲜程度。 */
public enum DataState {
    /** 数据在允许的时效内。 */
    FRESH,
    /** 数据已超过允许的时效。 */
    STALE,
    /** 缺少观测日期，无法判断。 */
    UNKNOWN
}
