package com.opspilot.ai.chat;

import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatGateway gateway;


    public ChatService(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public String chat(String message){
        return gateway.generate(message);
    }
}
