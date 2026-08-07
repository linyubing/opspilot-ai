package com.opspilot.ai.rag;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 提示词构建器。
 *
 * 负责把向量检索得到的文档块和用户问题
 * 组合成发送给大模型的最终提示词。
 */
public class RagPromptBuilder {

    public String build(String question, List<Document> documents){
        /*
         * 将多个文档块合并成一个上下文。
         * 分隔线用于避免不同文档块的内容混在一起。
         */
        String context = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        /*
         * 明确限制模型只能根据上下文回答，
         * 减少模型脱离知识库自由发挥的情况。
         */
        return """
                你是 OpsPilot AI 运维助手。
                只能根据以下上下文回答。
                如果上下文中没有答案，请明确回答“知识库中没有相关信息”。

                【上下文】
                %s

                【用户问题】
                %s
                """.formatted(context, question);
    }
}
