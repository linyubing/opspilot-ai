package com.opspilot.ai.ingestion.api;

import com.opspilot.ai.ingestion.DocumentChunker;
import com.opspilot.ai.ingestion.DocumentIngestionService;
import com.opspilot.ai.ingestion.DocumentUploadService;
import com.opspilot.ai.ingestion.TikaReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class DocumentControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(){
        /*
         * 当前还没有接入向量数据库。
         * 这里的 Writer 接收文档块但不持久化，
         * Controller 测试只关注 HTTP 上传协议。
         */
        DocumentWriter writer = documents -> {};

        DocumentIngestionService ingestionService =
                new DocumentIngestionService(new DocumentChunker(100),writer);

        DocumentUploadService uploadService=
                new DocumentUploadService(new TikaReaderFactory(),ingestionService);

        DocumentController controller = new DocumentController(uploadService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    // uploadsDocument：上传 TXT 文件后应返回摄取统计
    void uploadsDocument() throws Exception{
        MockMultipartFile file = new MockMultipartFile(// file：必须与 Controller 的请求参数名称一致
                "file",
                "运维手册.txt",
                "text/plain",
                "CPU 过高时应先检查高消耗进程"
                        .getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceDocumentCount").value(1))
                .andExpect(jsonPath("$.chunkCount").exists());

    }

    @Test
// rejectsEmptyFile：空文件不应进入 Tika 解析流程
    void rejectsEmptyFile() throws Exception{
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "空文件.txt",
                "text/plain",
                new byte[0]
        );

        mockMvc.perform(multipart("/api/documents").file(emptyFile))
                .andExpect(status().isBadRequest());
    }

}
