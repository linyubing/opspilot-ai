package com.opspilot.ai.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class FileTypeValidatorTests {

    @Test
    // acceptsTextFile：真实文本内容与 txt 扩展名一致时应通过
    void acceptsTextFile() throws Exception{
        Resource resource = new ByteArrayResource(
                "这是运维知识库内容".getBytes(StandardCharsets.UTF_8)
        );

        FileTypeValidator validator = new FileTypeValidator();

        boolean allowed = validator.isAllowed(resource,"运维手册.txt");
        assertThat(allowed).isTrue();
    }

    @Test
    // rejectsDisguisedExecutable：伪装成 PDF 的可执行文件应被拒绝
    void rejectsDisguisedExecutable() throws Exception{
        /*
         * Windows 可执行文件通常以 MZ 开头。
         * 文件名和客户端 Content-Type 都可以伪造，
         * 但文件头仍会暴露实际类型。
         */
        byte[] executableContent = """
                MZThis program cannot be run in DOS mode
                """.getBytes(StandardCharsets.US_ASCII);

        Resource resource = new ByteArrayResource(executableContent);
        FileTypeValidator validator =new FileTypeValidator();

        boolean allowed = validator.isAllowed(resource,"伪装文档.pdf");

        assertThat(allowed).isFalse();
    }
}
