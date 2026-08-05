package com.opspilot.ai.chat;

@FunctionalInterface
public interface ChatGateway {
    String generate(String message);
}
