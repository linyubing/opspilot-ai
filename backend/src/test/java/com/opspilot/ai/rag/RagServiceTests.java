package com.opspilot.ai.rag;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.retrieval.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class RagServiceTests {

    @Test
    void skipsModelWhenKnowledgeMissing(){
        KnowledgeSearchService searchService =
                mock(KnowledgeSearchService.class);
        ChatGateway chatGateway = mock(ChatGateway.class);
        RagPromptBuilder promptBuilder = new RagPromptBuilder();

        String question ="公司的数据库密码是多少？";

        //空集合表示向量库没有召回达到相似度阈值的文档。
        when(searchService.search(question)).thenReturn(List.of());

        RagService ragService = new RagService(searchService,promptBuilder,chatGateway);

        String answer = ragService.answer(question);

        assertThat(answer).isEqualTo("知识库中没有相关信息");

        verify(searchService).search(question);

        //没有相关知识时，不允许调用模型，避免幻觉和无效费用
        verifyNoInteractions(chatGateway);

    }

    @Test
    void answersWithKnowledgeContext(){
        KnowledgeSearchService searchService  = mock(KnowledgeSearchService.class);
        ChatGateway chatGateway = mock(ChatGateway.class);

        //提示词构建器使用的真实对象，避免测试只验证模拟对象之间的调用
        RagPromptBuilder promptBuilder = new RagPromptBuilder();

        String question = "CPU 使用率为什么持续过高？";
        List<Document> documents = List.of(
                new Document("CPU 持续过高时，应先检查消耗 CPU 较高的进程。")
        );
        when(searchService.search(question)).thenReturn(documents);
        when(chatGateway.generate(anyString())).thenReturn("请先检查 CPU 消耗较高的进程。");

        /*
         * RagService 是 RAG 的编排层：
         * 它不负责向量检索和模型调用的具体实现，
         * 只负责按照正确顺序协调三个组件。
         */
        RagService ragService = new RagService(searchService,promptBuilder,chatGateway);

        String answer = ragService.answer(question);
        assertThat(answer)
                .isEqualTo("请先检查 CPU 消耗较高的进程。");
        verify(searchService).search(question);

        /*
         * 验证发送给模型的提示词确实包含检索结果和用户问题，
         * 防止后续重构时漏掉知识库上下文。
         */
        verify(chatGateway).generate(
                org.mockito.ArgumentMatchers.argThat(prompt->
                        prompt.contains(question) && prompt.contains("检查消耗 CPU 较高的进程"))
        );
    }
}
