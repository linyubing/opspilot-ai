package com.opspilot.ai.rag;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.retrieval.KnowledgeSearchService;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * RAG 问答编排服务。
 *
 * 负责串联：
 * 1. 从知识库检索相关文档；
 * 2. 将文档和问题组装成提示词；
 * 3. 调用大模型生成最终回答。
 */
public class RagService {
    private final KnowledgeSearchService searchService;
    private final RagPromptBuilder promptBuilder;
    private final ChatGateway chatGateway;

    public RagService(KnowledgeSearchService searchService, RagPromptBuilder promptBuilder, ChatGateway chatGateway) {
        this.searchService = searchService;
        this.promptBuilder = promptBuilder;
        this.chatGateway = chatGateway;
    }

    public String answer(String question){
        //召回与用户问题语义相关的知识库文档
        List<Document> documents = searchService.search(question);

        /*
         * 没有召回相关文档时直接结束：
         * 既防止模型脱离知识库编造内容，也避免无效的模型调用费用。
         */
        if (documents.isEmpty()) {
            return "知识库中没有相关信息";
        }

        //将检索结果作为上下文，与用户问题共同组成受约束的提示词
        String prompt = promptBuilder.build(question,documents);

        return chatGateway.generate(prompt);
    }
}
