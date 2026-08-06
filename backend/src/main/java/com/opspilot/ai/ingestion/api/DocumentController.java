package com.opspilot.ai.ingestion.api;

import com.opspilot.ai.ingestion.DocumentUploadService;
import com.opspilot.ai.ingestion.IngestionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档上传接口。
 *
 * Controller 只负责处理 HTTP 协议，
 * 不直接操作 Tika、分块器或向量数据库。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentUploadService uploadService;

    public DocumentController(DocumentUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * 上传并摄取一份文档。
     *
     * @param file multipart/form-data 中名称为 file 的文件
     * @return 文档解析和分块数量
     */
    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public IngestionResult upload(
            @RequestParam("file") MultipartFile file
    ){
        /*
         * 空文件属于客户端请求错误，
         * 必须在进入 Tika 解析前直接拒绝。
         */
        if (file.isEmpty()){
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "上传文件不能为空"
            );
        }

        return uploadService.upload(file.getResource());
    }
}
