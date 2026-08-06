package com.opspilot.ai.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;

/**
 * 基于 Token 对文档进行分块。
 *
 * 实现 DocumentTransformer 后，可以直接注入
 * DocumentIngestionService 的 Transform 阶段。
 */
public class DocumentChunker implements DocumentTransformer {
    // Spring AI 提供的 Token 分块器
    private final TokenTextSplitter splitter;

    /**
     * @param chunkSize 每个文档块的目标 Token 数量
     */
    public DocumentChunker(int chunkSize) {
        this.splitter = TokenTextSplitter.builder()
                // 每个文档块的目标 Token 数量
                .withChunkSize(chunkSize)

                // 太短的字符片段不单独成为一个文档块
                .withMinChunkSizeChars(10)

                // 少于该长度的内容不进入后续向量化流程
                .withMinChunkLengthToEmbed(5)

                // 防止异常大文档产生无限多的文档块
                .withMaxNumChunks(10_000)

                // 尽量保留句号、逗号等分隔符，减少语义损失
                .withKeepSeparator(true)
                .build();
    }

    /**
     * apply 是 Function 接口的方法。
     * DocumentTransformer.transform() 最终会调用这里。
     */
    @Override
    public List<Document> apply(List<Document> documents) {
        return splitter.transform(documents);
    }
}
