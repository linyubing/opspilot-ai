package com.opspilot.ai.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 临时的内存文档库。
 *
 * 当前用于学习和验证文档摄取流程，
 * 后续会被真正的 VectorStore 替换。
 */
public class InMemoryDocumentStore implements DocumentWriter {

    //保持已经完成切片的文档块
    private final List<Document> documents =new ArrayList<>();


    /**
     * 将文档块写入内存。
     *
     * synchronized：防止多个上传请求同时修改 ArrayList，
     * 导致数据覆盖或集合状态损坏。
     */
    @Override
    public synchronized void write(List<Document> documents){
        this.documents.addAll(documents);
    }


    /**
     * 返回当前保存的全部文档块。
     *
     * List.copyOf：返回只读副本，避免调用方直接修改内部集合。
     */
    public synchronized List<Document> findAll(){
        return List.copyOf(documents);
    }


    @Override
    public void accept(List<Document> documents) {

    }

    @Override
    public Consumer<List<Document>> andThen(Consumer<? super List<Document>> after) {
        return DocumentWriter.super.andThen(after);
    }
}
