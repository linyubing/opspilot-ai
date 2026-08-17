package com.opspilot.ai.ingestion.api;

import com.opspilot.ai.ingestion.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class DocumentControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        /*
         * Controller 测试只关注 HTTP 协议和参数校验，
         * 上传生命周期由 DocumentUploadServiceTests 单独验证。
         */
        DocumentUploadService uploadService = mock(DocumentUploadService.class);
        when(uploadService.upload(any()))
                .thenReturn(new IngestionResult(1, 1));

        DocumentController controller = new DocumentController(
                uploadService,
                new FileTypeValidator()
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
// rejectsOversizedFile：超过 10 MB 的文件应在进入 Tika 解析前被拒绝
    void rejectsOversizedFile() throws Exception{
        byte[] content = new byte[10*1024*1024+1];
        // 全 0 字节会被 Tika 判断为二进制文件，无法真正测试“文件大小超限”。
        Arrays.fill(content, (byte) ' ');
        content[0] = 'A';

        MockMultipartFile oversizedFile = new MockMultipartFile(
                "file",
                "大型文档.txt",
                "text/plain",
                content
        );

        mockMvc.perform(multipart("/api/documents").file(oversizedFile))
                .andExpect(status().isBadRequest());
    }

    @Test
// rejectsDisguisedFileContent：扩展名伪装不能绕过真实类型校验
    void rejectsDisguisedFileContent() throws Exception{
        byte[] executableContent = """
            MZThis program cannot be run in DOS mode
            """.getBytes(StandardCharsets.US_ASCII);

        MockMultipartFile disguisedFile =
                new MockMultipartFile(
                        "file",
                        // 文件名故意伪装成 PDF
                        "伪装文档.pdf",
                        // 客户端声明的类型也故意伪造成 PDF
                        "application/pdf",
                        executableContent
                );

        mockMvc.perform(
                        multipart("/api/documents")
                                .file(disguisedFile)
                )
                .andExpect(status().isBadRequest());
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

    @Test
    // rejectsUnsupportedFileType：不支持的文件类型应返回 400
    void rejectsUnsupportedFileType() throws Exception{
        MockMultipartFile executableFile =
                new MockMultipartFile(  "file",
                        "恶意程序.exe",
                        "application/octet-stream",
                        "这不是真正的文档"
                                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents").file(executableFile))
                .andExpect(status().isBadRequest());
    }



}
