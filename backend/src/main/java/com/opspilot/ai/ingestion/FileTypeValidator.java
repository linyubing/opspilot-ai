package com.opspilot.ai.ingestion;

import org.apache.tika.Tika;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校验文件扩展名和真实内容类型是否匹配。
 */
public class FileTypeValidator {

    /*
     * MIME Type：用于描述文件真实内容类型的标准字符串。
     *
     * application/x-tika-msoffice：
     * Tika 对旧版 Microsoft Office 文件的内部识别结果。
     *
     * application/x-tika-ooxml：
     * Tika 对 docx 等新版 Office 文件的内部识别结果。
     */
    private static final Map<String, Set<String>> ALLOWED_TYPES =
            Map.of(
                    "txt", Set.of(
                            "text/plain"
                    ),
                    "pdf", Set.of(
                            "application/pdf"
                    ),
                    "doc", Set.of(
                            "application/msword",
                            "application/x-tika-msoffice"
                    ),
                    "docx", Set.of(
                            "application/vnd.openxmlformats-officedocument"
                                    + ".wordprocessingml.document",
                            "application/x-tika-ooxml"
                    )
            );

    private final Tika tika = new Tika();

    /**
     * 判断文件扩展名与实际内容类型是否匹配。
     *
     * @param resource 文件内容
     * @param filename 原始文件名
     */
    public boolean isAllowed(Resource resource,String filename) throws IOException {
        String extension = StringUtils.getFilenameExtension(filename);

        if (extension==null){
            return false;
        }
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        Set<String> expectedTypes = ALLOWED_TYPES.get(normalizedExtension);
        if(expectedTypes==null){
            return false;
        }
        /*
         * try-with-resources：方法执行结束后自动关闭输入流，
         * 避免上传多个文件后出现文件句柄或内存资源泄漏。
         */
        try(InputStream inputStream = resource.getInputStream()){
            /*
             * 不传客户端声明的 Content-Type，
             * 让 Tika 根据文件字节内容自行识别。
             */
            String detectedType = tika.detect(inputStream);
            return expectedTypes.contains(detectedType);
        }
    }
}
