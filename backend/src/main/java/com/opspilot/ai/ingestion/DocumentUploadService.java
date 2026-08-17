package com.opspilot.ai.ingestion;

import com.opspilot.ai.document.ContentHashCalculator;
import com.opspilot.ai.document.DocumentRepository;
import com.opspilot.ai.document.DocumentStatus;
import com.opspilot.ai.document.DuplicateDocumentException;
import com.opspilot.ai.document.KnowledgeDocument;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * 文档上传用例服务。
 * <p>
 * 负责把上传的文件转换成 DocumentReader，
 * 然后交给文档摄取服务完成解析、分块和写入。
 */
public class DocumentUploadService {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final TikaReaderFactory readerFactory;
    private final DocumentIngestionService ingestionService;
    private final ContentHashCalculator hashCalculator;
    private final DocumentRepository repository;
    private final VectorStore vectorStore;

    public DocumentUploadService(
            TikaReaderFactory readerFactory,
            DocumentIngestionService ingestionService,
            ContentHashCalculator hashCalculator,
            DocumentRepository repository,
            VectorStore vectorStore
    ) {
        this.readerFactory = readerFactory;
        this.ingestionService = ingestionService;
        this.hashCalculator = hashCalculator;
        this.repository = repository;
        this.vectorStore = vectorStore;
    }

    /**
     * 上传并摄取一份文档。
     *
     * @param resource 上传文件对应的 Spring Resource
     * @return 原始文档数量和分块数量
     */
    public IngestionResult upload(Resource resource) {
        //使用Tika 为当前上传文件创建读取器
        DocumentReader reader =
                readerFactory.create(resource);

        //执行读取、分块、写入完整流程
        return ingestionService.ingest(reader);
    }

    /**
     * 上传文档并管理完整处理生命周期
     */
    public KnowledgeDocument upload(
            Resource resource,
            String filename
    ) throws IOException {
        byte[] content;

        /*
         * 最多读取10MB再多一个字节。
         * 多出的一个字节用于判断文件是否超过限制。
         */
        try (InputStream inputStream = resource.getInputStream()) {
            content = inputStream.readNBytes(MAX_FILE_SIZE + 1);
        }

        if (content.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("上传文件不能超过10MB");
        }

        String contentHash = hashCalculator.calculate(content);
        Optional<KnowledgeDocument> existing = repository.findByHash(contentHash);

        KnowledgeDocument processingDocument;

        if (existing.isEmpty()) {
            try {
                processingDocument = repository.create(
                        UUID.randomUUID(),
                        filename,
                        contentHash
                );
            } catch (DuplicateKeyException exception) {
                /*
                 * 查询与插入之间仍可能发生并发上传，
                 * 数据库唯一约束是最终的防重复屏障。
                 */
                throw new DuplicateDocumentException(
                        "相同内容的文档已经存在",
                        exception
                );
            }
        } else if (existing.get().status() == DocumentStatus.FAILED) {
            //失败文档重新上传时复用原ID,避免产生多条相同内容的记录
            processingDocument = existing.get();
            repository.restart(processingDocument.id());
        } else {
            throw new DuplicateDocumentException("相同内容的文档已经存在");
        }

        //前面读取文件是为了计算哈希，这里重新包装字节，让Tika可以再次读取文件内容
        Resource repeatableResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        try {
            DocumentReader reader = readerFactory.create(repeatableResource);
            IngestionResult result = ingestionService.ingest(reader, processingDocument.id(), filename);

            repository.markReady(processingDocument.id(), result.chunkCount());
            return repository.findById(processingDocument.id()).orElseThrow();
        } catch (RuntimeException exception) {
            //导入向量过程中可能只成功了一部分，根据documentId删除本次产生的全部残留切片
            vectorStore.delete("documentId == '" + processingDocument.id() + "'");

            repository.markFailed(processingDocument.id(), exception.getMessage());

            // 继续抛出原异常，保留真正的失败原因。
            throw exception;
        }
    }
}
