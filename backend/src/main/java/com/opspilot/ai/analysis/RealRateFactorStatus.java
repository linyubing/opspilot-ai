package com.opspilot.ai.analysis;

/**
 * 表示实际利率对黄金的单因子研究状态，不代表交易信号。
 */
public enum RealRateFactorStatus {
    /** 压力因素 */
    PRESSURING,
    /** 支撑因素 */
    SUPPORTIVE,
    /** 中性或方向冲突 */
    NEUTRAL
}
