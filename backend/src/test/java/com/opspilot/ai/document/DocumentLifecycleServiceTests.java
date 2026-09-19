package com.opspilot.ai.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentLifecycleServiceTests {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final VectorStore vectorStore = mock(VectorStore.class);

    private DocumentLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new DocumentLifecycleService(repository, vectorStore);
    }

    @Test
    @DisplayName("查询文档列表时返回数据访问层的排序结果")
    void listsDocuments() {
        KnowledgeDocument document = document();
        when(repository.findAll()).thenReturn(List.of(document));

        List<KnowledgeDocument> result = service.list();

        assertThat(result).containsExactly(document);
    }

    @Test
    @DisplayName("删除文档时先删除关联向量再删除文档记录")
    void deletesVectorsBeforeDocumentRecord() {
        when(repository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document()));

        service.delete(DOCUMENT_ID);

        var order = inOrder(vectorStore, repository);
        order.verify(vectorStore)
                .delete("documentId == '" + DOCUMENT_ID + "'");
        order.verify(repository).deleteById(DOCUMENT_ID);
    }

    @Test
    @DisplayName("删除不存在的文档时抛出异常且不操作向量库")
    void rejectsMissingDocument() {
        when(repository.findById(DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(DOCUMENT_ID))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(vectorStore, never()).delete(
                "documentId == '" + DOCUMENT_ID + "'"
        );
        verify(repository, never()).deleteById(DOCUMENT_ID);
    }

    private KnowledgeDocument document() {
        OffsetDateTime now = OffsetDateTime.now();
        return new KnowledgeDocument(
                DOCUMENT_ID,
                "运维手册.txt",
                "a".repeat(64),
                DocumentStatus.READY,
                3,
                null,
                now,
                now
        );
    }
}
