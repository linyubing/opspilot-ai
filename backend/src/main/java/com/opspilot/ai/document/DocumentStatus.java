package com.opspilot.ai.document;

/**
 * 文档从上传到建立向量索引期间的处理状态。
 */
public enum DocumentStatus {

    // 正在解析、切片和生成向量。
    PROCESSING,

    // 文档及其向量切片均已成功保存。
    READY,

    // 处理失败，允许后续使用同一条记录重试。
    FAILED
}
