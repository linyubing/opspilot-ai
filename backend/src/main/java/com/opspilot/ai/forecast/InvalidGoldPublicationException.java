package com.opspilot.ai.forecast;

/** 预测发布时机无效时拒绝新建，避免事后行情进入未来评测。 */
public class InvalidGoldPublicationException extends RuntimeException {
    public InvalidGoldPublicationException(String message) {
        super(message);
    }
}
