package com.opspilot.ai.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * 知识库检索服务。
 *
 * 负责根据用户问题，从向量存储中召回语义相关的文档块。
 * 当前只实现最小检索能力，后续再加入 topK 和相似度阈值。
 */
public class KnowledgeSearchService {

    private final VectorStore vectorStore;


    public KnowledgeSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 根据问题进行语义相似度检索。
     *
     * 这里不是数据库中的关键词 LIKE 查询，
     * VectorStore 会先将问题转换成向量，再比较向量之间的距离。
     */
    public List<Document> search(String question){
        /*
         * topK(3)：最多召回 3 个文档块，控制上下文长度和模型成本。
         * similarityThreshold(0.7)：过滤相关度过低的内容，减少错误上下文。
         */
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(3)
                .similarityThreshold(0.7)
                .build();

        return vectorStore.similaritySearch(request);
    }
}
