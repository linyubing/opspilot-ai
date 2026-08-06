package com.opspilot.ai.ingestion;

import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.Resource;

/**
 * 文档上传用例服务。
 *
 * 负责把上传的文件转换成 DocumentReader，
 * 然后交给文档摄取服务完成解析、分块和写入。
 */
public class DocumentUploadService {

    private final TikaReaderFactory readerFactory;
    private final DocumentIngestionService ingestionService;

    public DocumentUploadService(TikaReaderFactory readerFactory, DocumentIngestionService ingestionService) {
        this.readerFactory = readerFactory;
        this.ingestionService = ingestionService;
    }

    /**
     * 上传并摄取一份文档。
     *
     * @param resource 上传文件对应的 Spring Resource
     * @return 原始文档数量和分块数量
     */
    public  IngestionResult upload(Resource resource){
        //使用Tika 为当前上传文件创建读取器
        DocumentReader reader =
                readerFactory.create(resource);

        //执行读取、分块、写入完整流程
        return ingestionService.ingest(reader);
    }
}
