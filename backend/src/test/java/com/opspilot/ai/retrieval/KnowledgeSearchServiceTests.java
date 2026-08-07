package com.opspilot.ai.retrieval;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KnowledgeSearchServiceTests {

    @Test
    void searchesRelevantDocuments(){
        VectorStore vectorStore = mock(VectorStore.class);

        List<Document> expected = List.of(
                new Document("CPU 过高时，应先检查高消耗进程")
        );

        /*
         * SearchRequest 封装向量检索参数：
         * query 是查询文本；
         * topK 是最多召回多少个文档块；
         * similarityThreshold 是最低相似度。
         */
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(expected);

        KnowledgeSearchService service = new KnowledgeSearchService(vectorStore);

        List<Document> result =service.search("服务器 CPU 为什么过高？");

        assertThat(result).isEqualTo(expected);

        ArgumentCaptor<SearchRequest> captor =
                ArgumentCaptor.forClass(SearchRequest.class);

        verify(vectorStore).similaritySearch(captor.capture());

        SearchRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("服务器 CPU 为什么过高？");
        assertThat(request.getTopK()).isEqualTo(3);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.7);

    }
}
