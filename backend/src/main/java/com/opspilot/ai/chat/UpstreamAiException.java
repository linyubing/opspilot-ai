package com.opspilot.ai.chat;

public class UpstreamAiException extends RuntimeException {
    public UpstreamAiException(String message,Throwable cause){
        super(message, cause);
    }
}
