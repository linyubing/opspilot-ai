package com.opspilot.ai.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TikaReaderFactoryTests {
    @Test
    // readTextFile：验证 Tika 能读取 TXT 文件中的中文内容
    void readTextFile(){
        /*
         * ByteArrayResource：使用内存模拟上传文件。
         * 测试不依赖本地磁盘路径，在其他机器上也能运行。
         */
        Resource resource = new ByteArrayResource(
                "OpsPilot AI 是一个智能运维助手"
                        .getBytes(StandardCharsets.UTF_8)
        ){
            @Override
            public String getFilename(){
                return "知识库.txt";
            }
        };

        TikaReaderFactory factory = new TikaReaderFactory();

        //create:根据上传的文件资源创建Spring ai 文档读取器
        DocumentReader reader = factory.create(resource);
        List<Document> documents = reader.read();

        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst().getText())
                .contains("OpsPilot AI 是一个智能运维助手");
    }
}
