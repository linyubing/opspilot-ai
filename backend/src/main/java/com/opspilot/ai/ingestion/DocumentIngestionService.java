package com.opspilot.ai.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.document.DocumentWriter;

import java.util.List;

/**
 * 文档摄取服务。
 *
 * Spring AI 的文档摄取采用 ETL 流程：
 * Extract：读取文档
 * Transform：清洗、切片
 * Load：写入向量存储
 */
public class DocumentIngestionService {
    //Transform: 复杂把原始文档转换成多个文档切片
    private final DocumentTransformer transformer;

    //Load:负责将文档切片写入存储，后续会替换成 VectorStore
    private final DocumentWriter writer;

    public DocumentIngestionService(DocumentTransformer transformer,
                                    DocumentWriter writer){
        this.transformer=transformer;
        this.writer=writer;
    }

    /**
     * 执行一次完整的文档摄取。
     *
     * ingest 表示“摄取”：把外部文档处理后写入知识库。
     */
    public IngestionResult ingest(DocumentReader reader){
        //Extract: 从本次输入资源中读取原始文档
        List<Document> sourceDocument = reader.read();

        //Transform:对原始文档进行清洗、切片
        List<Document> chunks =
                transformer.transform(sourceDocument);

        //Load:将切片写入存储，后续实际写入向量数据库
        writer.write(chunks);

        return new IngestionResult(sourceDocument.size(),
                chunks.size());
    }

}
