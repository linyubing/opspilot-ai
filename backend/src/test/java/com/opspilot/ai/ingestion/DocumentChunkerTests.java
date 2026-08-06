package com.opspilot.ai.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class DocumentChunkerTests {

    @Test
    // splitKeepsMetadata：切片后必须保留原始文件的元数据
    void splitKeepsMetadata(){
        /*
         * repeat(100)：构造足够长的文档，确保能够产生多个切片。
         * source 元数据后续用于告诉用户答案来自哪份文档。
         */
        String content = """
                CPU 使用率持续超过 90% 时，应先定位高消耗进程，
                然后检查线程、数据库慢查询和外部接口调用情况。
                """.repeat(100);

        Document source = new Document(
                content,
                Map.of("source", "运维手册.txt")
        );

        /*
         * 100 表示每个文档块的目标 Token 数量。
         * Token 不是字符，一个中文字符可能对应一个或多个 Token。
         */
        DocumentChunker chunker = new DocumentChunker(100);

        List<Document> chunks =
                chunker.transform(List.of(source));
        assertThat(chunks).hasSizeGreaterThan(1);

        // 每个切片都必须保留来源，否则检索后无法展示引用文档
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.getMetadata())
                        .containsEntry("source", "运维手册.txt")
        );
    }
}
