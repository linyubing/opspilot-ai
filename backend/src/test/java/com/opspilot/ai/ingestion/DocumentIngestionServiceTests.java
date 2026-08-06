package com.opspilot.ai.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.document.DocumentWriter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class DocumentIngestionServiceTests {
    @Test
    // ingestWritesChunks：摄取服务应将解析结果切片后写入存储
    void ingestWritesChunks(){
        /*
         * DocumentReader 是 Spring AI ETL 的 Extract 阶段。
         * 真实环境后续使用 TikaDocumentReader，这里用内存实现隔离文件解析。
         */
        DocumentReader reader = () -> List.of(
                new Document("原始文档内容")
        );

        /*
         * DocumentTransformer 是 Transform 阶段。
         * 真实环境后续使用 TokenTextSplitter，这里固定产生两个切片，
         * 当前测试只关注流水线编排，不测试切片算法。
         */
        DocumentTransformer splitter = documents ->List.of (
                new Document("第一个切片"),
                new Document("第二个切片")
                );

        //捕获DocumentWriter 收到的内容，模拟向量库写入
        AtomicReference<List<Document>> writtenDocuments =
                new AtomicReference<>();

        /*
         * DocumentWriter 是 Load 阶段。
         * VectorStore 实现了该接口，后续可以无缝替换成 pgvector。
         */
        DocumentWriter writer = writtenDocuments::set;

        DocumentIngestionService service =
                new DocumentIngestionService(splitter,writer);

        IngestionResult result = service.ingest(reader);

        assertThat(result.sourceDocumentCount()).isEqualTo(1);
        assertThat(result.chunkCount()).isEqualTo(2);

        assertThat(writtenDocuments.get())
                .extracting(Document::getText)
                .containsExactly("第一个切片","第二个切片");
    }
}
