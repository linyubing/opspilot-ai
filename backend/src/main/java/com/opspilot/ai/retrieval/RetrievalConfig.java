package com.opspilot.ai.retrieval;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识检索模块配置。
 *
 * 检索模块只依赖 VectorStore 抽象，
 * 不关心底层使用 SimpleVectorStore、PGVector 还是其他向量库。
 */
@Configuration
public class RetrievalConfig {
    /**
     * 创建知识库检索服务。
     */
    @Bean
    public KnowledgeSearchService knowledgeSearchService(VectorStore vectorStore){
        return new KnowledgeSearchService(vectorStore);
    }

}
