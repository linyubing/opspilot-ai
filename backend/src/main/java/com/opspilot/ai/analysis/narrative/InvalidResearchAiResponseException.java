package com.opspilot.ai.analysis.narrative;

/** 表示大模型响应无法转换为约定的结构化研究解读。 */
public class InvalidResearchAiResponseException extends RuntimeException {

    public InvalidResearchAiResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
