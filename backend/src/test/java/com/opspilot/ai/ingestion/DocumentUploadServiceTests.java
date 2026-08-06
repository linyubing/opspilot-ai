package com.opspilot.ai.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class DocumentUploadServiceTests {

    @Test
    // uploadRunsPipeline：上传文件后应完成解析、分块和写入
    void  uploadRunsPipeline(){
        String content = """
                数据库连接数过高时，应检查连接池配置、慢查询、
                未释放连接以及突发流量情况。
                """.repeat(100);

        Resource resource = new ByteArrayResource(
                content.getBytes(StandardCharsets.UTF_8)
        ){
            @Override
            public String getFilename() {
                return "数据库排障手册.txt";
            }
        };

        //捕获最终写入的文档块，暂时代替真正的向量数据库
        AtomicReference<List<Document>> written =
                new AtomicReference<>();

        DocumentWriter writer = written::set;

        DocumentIngestionService ingestionService =
                new DocumentIngestionService(
                        new DocumentChunker(100),writer
                );

        DocumentUploadService uploadService =
                new DocumentUploadService(new TikaReaderFactory(),
                        ingestionService);

        IngestionResult result = uploadService.upload(resource);

        assertThat(result.sourceDocumentCount()).isEqualTo(1);
        assertThat(result.chunkCount()).isGreaterThan(1);

        assertThat(written.get())
                .hasSize(result.chunkCount());
    }
}
