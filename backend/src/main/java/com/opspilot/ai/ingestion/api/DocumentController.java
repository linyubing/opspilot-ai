package com.opspilot.ai.ingestion.api;

import com.opspilot.ai.ingestion.DocumentUploadService;
import com.opspilot.ai.ingestion.FileTypeValidator;
import com.opspilot.ai.ingestion.IngestionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 文档上传接口。
 * <p>
 * Controller 只负责处理 HTTP 协议，
 * 不直接操作 Tika、分块器或向量数据库。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final FileTypeValidator typeValidator;

    /**
     * 当前允许进入文档解析流程的文件扩展名。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "pdf", "doc", "docx");

    public DocumentController(DocumentUploadService uploadService, FileTypeValidator typeValidator) {
        this.uploadService = uploadService;
        this.typeValidator = typeValidator;
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
            @RequestParam("file") MultipartFile file) throws IOException {
        /*
         * 读取文件真实字节类型，并与扩展名进行匹配。
         * 不能相信客户端提交的 Content-Type。
         */
        boolean allowed = typeValidator.isAllowed(file.getResource(), file.getOriginalFilename());

        if(!allowed){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文件扩展名与真实内容类型不匹配");
        }

        /*
         * 空文件属于客户端请求错误，
         * 必须在进入 Tika 解析前直接拒绝。
         */
        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "上传文件不能为空"
            );
        }

        /*
         * getFilenameExtension：获取文件名最后一个点号后的扩展名。
         * 例如“运维手册.PDF”得到“PDF”。
         */
        String extension = StringUtils.getFilenameExtension(file.getOriginalFilename());

        /*
         * Locale.ROOT：使用与用户语言无关的大小写转换规则，
         * 避免某些语言环境下大小写转换结果异常。
         */
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 txt、pdf、doc、docx 文件");
        }


        return uploadService.upload(file.getResource());
    }
}
