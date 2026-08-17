package com.opspilot.ai.ingestion;

import com.opspilot.ai.document.ContentHashCalculator;
import com.opspilot.ai.document.DocumentRepository;
import com.opspilot.ai.document.DocumentStatus;
import com.opspilot.ai.document.DuplicateDocumentException;
import com.opspilot.ai.document.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentUploadServiceTests {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final String FILENAME = "运维手册.txt";
    private static final String CONTENT_HASH = "a".repeat(64);

    private final TikaReaderFactory readerFactory = mock(TikaReaderFactory.class);
    private final DocumentIngestionService ingestionService =
            mock(DocumentIngestionService.class);
    private final ContentHashCalculator hashCalculator =
            mock(ContentHashCalculator.class);
    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final DocumentReader reader = mock(DocumentReader.class);

    private DocumentUploadService uploadService;
    private Resource resource;

    @BeforeEach
    void setUp() throws IOException {
        uploadService = new DocumentUploadService(
                readerFactory,
                ingestionService,
                hashCalculator,
                repository,
                vectorStore
        );

        resource = new ByteArrayResource(
                "文档内容".getBytes(StandardCharsets.UTF_8)
        );
        when(hashCalculator.calculate(any())).thenReturn(CONTENT_HASH);
        when(readerFactory.create(any())).thenReturn(reader);
    }

    @Test
    @DisplayName("内容相同的成功文档不允许重复上传")
    void rejectsDuplicateDocument() {
        when(repository.findByHash(CONTENT_HASH))
                .thenReturn(Optional.of(document(DocumentStatus.READY, 3)));

        assertThatThrownBy(() -> uploadService.upload(resource, FILENAME))
                .isInstanceOf(DuplicateDocumentException.class);

        verify(ingestionService, never())
                .ingest(any(), any(), any());
    }

    @Test
    @DisplayName("新文档完成向量写入后更新为成功状态")
    void marksNewDocumentReady() throws IOException {
        KnowledgeDocument processing = document(DocumentStatus.PROCESSING, 0);
        KnowledgeDocument ready = document(DocumentStatus.READY, 3);

        when(repository.findByHash(CONTENT_HASH)).thenReturn(Optional.empty());
        when(repository.create(any(), eq(FILENAME), eq(CONTENT_HASH)))
                .thenReturn(processing);
        when(ingestionService.ingest(reader, DOCUMENT_ID, FILENAME))
                .thenReturn(new IngestionResult(1, 3));
        when(repository.findById(DOCUMENT_ID)).thenReturn(Optional.of(ready));

        KnowledgeDocument result = uploadService.upload(resource, FILENAME);

        verify(repository).markReady(DOCUMENT_ID, 3);
        assertThat(result.status()).isEqualTo(DocumentStatus.READY);
        assertThat(result.chunkCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("向量写入失败时删除残留向量并记录失败原因")
    void compensatesFailedIngestion() {
        KnowledgeDocument processing = document(DocumentStatus.PROCESSING, 0);
        IllegalStateException failure = new IllegalStateException("向量服务不可用");

        when(repository.findByHash(CONTENT_HASH)).thenReturn(Optional.empty());
        when(repository.create(any(), eq(FILENAME), eq(CONTENT_HASH)))
                .thenReturn(processing);
        when(ingestionService.ingest(reader, DOCUMENT_ID, FILENAME))
                .thenThrow(failure);

        assertThatThrownBy(() -> uploadService.upload(resource, FILENAME))
                .isSameAs(failure);

        verify(vectorStore).delete(
                "documentId == '" + DOCUMENT_ID + "'"
        );
        verify(repository).markFailed(DOCUMENT_ID, "向量服务不可用");
    }

    @Test
    @DisplayName("处理失败的文档再次上传时复用原记录重试")
    void retriesFailedDocument() throws IOException {
        KnowledgeDocument failed = document(DocumentStatus.FAILED, 0);
        KnowledgeDocument ready = document(DocumentStatus.READY, 2);

        when(repository.findByHash(CONTENT_HASH))
                .thenReturn(Optional.of(failed));
        when(ingestionService.ingest(reader, DOCUMENT_ID, FILENAME))
                .thenReturn(new IngestionResult(1, 2));
        when(repository.findById(DOCUMENT_ID)).thenReturn(Optional.of(ready));

        KnowledgeDocument result = uploadService.upload(resource, FILENAME);

        verify(repository).restart(DOCUMENT_ID);
        verify(repository, never()).create(any(), any(), any());
        verify(repository).markReady(DOCUMENT_ID, 2);
        assertThat(result.status()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    @DisplayName("并发上传触发内容哈希唯一约束时转换为重复文档异常")
    void convertsConcurrentDuplicate() {
        when(repository.findByHash(CONTENT_HASH)).thenReturn(Optional.empty());
        when(repository.create(any(), eq(FILENAME), eq(CONTENT_HASH)))
                .thenThrow(new DuplicateKeyException("内容哈希重复"));

        assertThatThrownBy(() -> uploadService.upload(resource, FILENAME))
                .isInstanceOf(DuplicateDocumentException.class);

        verify(ingestionService, never())
                .ingest(any(), any(), any());
    }

    private KnowledgeDocument document(DocumentStatus status, int chunkCount) {
        OffsetDateTime now = OffsetDateTime.now();
        return new KnowledgeDocument(
                DOCUMENT_ID,
                FILENAME,
                CONTENT_HASH,
                status,
                chunkCount,
                null,
                now,
                now
        );
    }
}
