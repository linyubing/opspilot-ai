package com.opspilot.ai.document;

import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.UUID;

/**
 * 管理知识库文档的查询和删除生命周期。
 */
public class DocumentLifecycleService {

    private final DocumentRepository repository;
    private final VectorStore vectorStore;

    public DocumentLifecycleService(
            DocumentRepository repository,
            VectorStore vectorStore
    ) {
        this.repository = repository;
        this.vectorStore = vectorStore;
    }

    public List<KnowledgeDocument> list() {
        return repository.findAll();
    }

    public void delete(UUID documentId) {
        repository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(
                        "文档不存在：" + documentId
                ));

        vectorStore.delete("documentId == '" + documentId + "'");
        repository.deleteById(documentId);
    }
}
