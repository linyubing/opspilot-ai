package com.opspilot.ai.analysis.history;

/**
 * 表示历史快照查询参数不符合公开 API 约束。
 */
public class InvalidResearchHistoryRequestException
        extends RuntimeException {

    public InvalidResearchHistoryRequestException(String message) {
        super(message);
    }
}
