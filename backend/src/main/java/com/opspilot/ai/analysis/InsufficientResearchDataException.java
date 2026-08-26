package com.opspilot.ai.analysis;

/**
 * 表示现有数据数量不足，无法生成完整研究快照。
 */
public class InsufficientResearchDataException extends RuntimeException {

    public InsufficientResearchDataException(String message) {
        super(message);
    }
}
