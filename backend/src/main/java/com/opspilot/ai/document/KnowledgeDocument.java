package com.opspilot.ai.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeDocument(
        UUID id,
        String filename,
        String contentHash,
        DocumentStatus status,
        int chunkCount,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
