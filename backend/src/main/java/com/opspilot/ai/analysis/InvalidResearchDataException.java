package com.opspilot.ai.analysis;

/**
 * 表示研究数据违反日期、价格或完整性要求。
 */
public class InvalidResearchDataException extends RuntimeException {

    public InvalidResearchDataException(String message) {
        super(message);
    }
}
