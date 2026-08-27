package com.opspilot.ai.analysis.narrative;

/** 表示黄金研究解读使用的大模型服务暂时不可用。 */
public class ResearchAiUnavailableException extends RuntimeException {

    public ResearchAiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
