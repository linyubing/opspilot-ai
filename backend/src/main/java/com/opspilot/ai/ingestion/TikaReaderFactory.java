package com.opspilot.ai.ingestion;

import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.ai.document.DocumentReader;

/**
 * 创建Tika 文档读取器
 *
 * 这个工厂用于隔离 Apache Tika 的具体实现，
 * 上层只需要依赖 Spring AI 的 DocumentReader。
 */
public class TikaReaderFactory {

    /**
     * 根据上传的文件资源创建文档读取器。
     *
     * @param resource Spring 对文件、字节流等资源的统一抽象
     * @return Spring AI 文档读取器
     */
    public DocumentReader create(Resource resource){
        return new TikaDocumentReader(resource);
    }
}
