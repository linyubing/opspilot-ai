package com.opspilot.ai.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    Optional<KnowledgeDocument> findByHash(String contentHash);

    Optional<KnowledgeDocument> findById(UUID id);

    List<KnowledgeDocument> findAll();

    KnowledgeDocument create(
            UUID id,
            String filename,
            String contentHash
    );

    void restart(UUID id);

    void markReady(UUID id, int chunkCount);

    void markFailed(UUID id, String errorMessage);

    void deleteById(UUID id);
}
