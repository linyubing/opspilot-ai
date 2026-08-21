package com.opspilot.ai.macrodata;

/**
 * 单条宏观观测保存后的版本变化结果。
 */
public enum SaveObservationResult {

    /** 新增 */
    INSERTED,

    /** 已修订 */
    REVISED,

    /** 未变化 */
    UNCHANGED
}
