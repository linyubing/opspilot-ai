package com.opspilot.ai.ingestion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档摄取模块的 Spring 配置。
 *
 * 统一负责创建解析器、分块器、存储和业务服务，
 * 避免在各个类上分散使用 @Component。
 */
@Configuration
public class IngestionConfig {

    /**
     * 创建真实文件类型校验器。
     */
    @Bean
    public FileTypeValidator fileTypeValidator(){
        return new FileTypeValidator();
    }

    /**
     * 创建 Tika 文档读取器工厂。
     */
    @Bean
    public TikaReaderFactory tikaReaderFactory(){
        return new TikaReaderFactory();
    }

    /**
     * 创建文档分块器。
     *
     * 500 Token 是当前学习阶段的初始值，
     * 后续会根据召回效果进行调整。
     */
    @Bean
    public DocumentChunker documentChunker(){
        return new DocumentChunker(500);
    }

    /**
     * 创建临时内存文档库。
     *
     * Spring Bean 默认是单例，因此所有上传请求
     * 会共享同一个内存文档库实例。
     */
    @Bean
    public InMemoryDocumentStore documentStore(){
        return new InMemoryDocumentStore();
    }

    /**
     * 创建通用文档摄取服务。
     */
    @Bean
    public DocumentIngestionService ingestionService(DocumentChunker chunker,
                                                     InMemoryDocumentStore store){
        return new DocumentIngestionService(chunker,store);
    }

    /**
     * 创建文档上传用例服务。
     */
    @Bean
    public DocumentUploadService uploadService(
            TikaReaderFactory readerFactory,
            DocumentIngestionService ingestionService
    ){
        return new DocumentUploadService(readerFactory,ingestionService);

    }
}
