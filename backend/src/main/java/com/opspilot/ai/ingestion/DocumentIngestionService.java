package com.opspilot.ai.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.document.DocumentWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档摄取服务。
 *
 * Spring AI 的文档摄取采用 ETL 流程：
 * Extract：读取文档
 * Transform：清洗、切片
 * Load：写入向量存储
 */
public class DocumentIngestionService {
    // Transform：负责把原始文档转换成多个文档切片。
    private final DocumentTransformer transformer;

    // Load：负责将文档切片写入存储，生产环境使用 VectorStore。
    private final DocumentWriter writer;

    public DocumentIngestionService(DocumentTransformer transformer,
                                    DocumentWriter writer) {
        this.transformer = transformer;
        this.writer = writer;
    }

    /**
     * 执行一次完整的文档摄取。
     *
     * ingest 表示“摄取”：把外部文档处理后写入知识库。
     */
    public IngestionResult ingest(DocumentReader reader) {
        // Extract：从本次输入资源中读取原始文档。
        List<Document> sourceDocuments = reader.read();

        // Transform：对原始文档进行清洗、切片。
        List<Document> chunks =
                transformer.transform(sourceDocuments);

        // Load：将切片写入存储。
        writer.write(chunks);

        return new IngestionResult(
                sourceDocuments.size(),
                chunks.size()
        );
    }

    /**
     * 摄取文档并给每个切片附加所属文档的身份信息。
     */
    public IngestionResult ingest(
            DocumentReader reader,
            UUID documentId,
            String filename
    ) {
        List<Document> sourceDocuments = reader.read();
        List<Document> chunks =
                transformer.transform(sourceDocuments);

        List<Document> enrichedChunks =
                new ArrayList<>(chunks.size());

        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = chunks.get(index);

            /*
             * mutate() 基于原切片创建新对象，
             * 会保留文本及 Tika、分块器已经产生的 metadata。
             */
            Document enrichedChunk = chunk.mutate()
                    .metadata("documentId", documentId.toString())
                    .metadata("filename", filename)
                    .metadata("chunkIndex", index)
                    .build();

            enrichedChunks.add(enrichedChunk);
        }

        writer.write(enrichedChunks);

        return new IngestionResult(
                sourceDocuments.size(),
                enrichedChunks.size()
        );
    }
}
