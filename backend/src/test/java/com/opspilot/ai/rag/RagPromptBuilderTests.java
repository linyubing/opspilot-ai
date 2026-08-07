package com.opspilot.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class RagPromptBuilderTests {

    @Test
    void buildsPromptWithContext(){
        List<Document> documents =List.of(
                new Document("CPU 过高时，应先检查高消耗进程。"),
                new Document("如果问题出现在发布后，应检查近期发布记录。")
        );

        RagPromptBuilder builder = new RagPromptBuilder();

        String prompt = builder.build(
                "服务器 CPU 为什么过高？",
                documents
        );

        // 提示词必须包含用户原始问题和召回到的知识内容
        assertThat(prompt)
                .contains("服务器 CPU 为什么过高？")
                .contains("CPU 过高时，应先检查高消耗进程。")
                .contains("如果问题出现在发布后，应检查近期发布记录。")
                .contains("只能根据以下上下文回答");

    }
}
