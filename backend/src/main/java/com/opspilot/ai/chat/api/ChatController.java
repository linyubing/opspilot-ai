package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping(produces = "application/json;charset=UTF-8")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request){
        String content = service.chat(request.message());
        return new ChatResponse(content);
    }
}
