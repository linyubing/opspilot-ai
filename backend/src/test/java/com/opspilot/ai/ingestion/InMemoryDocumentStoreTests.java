package com.opspilot.ai.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class InMemoryDocumentStoreTests {
    @Test
    // writeStoresDocuments：写入的文档块应能从内存库中查询
    void writeStoresDocuments(){
        InMemoryDocumentStore store =
                new InMemoryDocumentStore();

        Document first = new Document(
                "第一个文档块",
                Map.of("source", "运维手册.txt")
        );

        Document second = new Document(
                "第二个文档块",
                Map.of("source", "运维手册.txt")
        );

        store.write(List.of(first, second));

        assertThat(store.findAll())
                .extracting(Document::getText)
                .containsExactly(
                        "第一个文档块",
                        "第二个文档块"
                );
    }
}
