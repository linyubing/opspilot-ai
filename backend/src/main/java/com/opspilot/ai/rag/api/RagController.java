package com.opspilot.ai.rag.api;

import com.opspilot.ai.chat.api.ChatRequest;
import com.opspilot.ai.chat.api.ChatResponse;
import com.opspilot.ai.rag.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库问答接口。
 *
 * 与普通聊天接口分开，便于调用方明确选择：
 * /api/chat     表示普通模型对话；
 * /api/rag/chat 表示基于知识库的问答。
 */
@RestController
@RequestMapping("/api/rag/chat")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping(produces = "application/json;charset=UTF-8")
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request
    ) {
        String content = ragService.answer(request.message());
        return new ChatResponse(content);
    }
}
