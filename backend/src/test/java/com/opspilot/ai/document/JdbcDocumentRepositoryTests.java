package com.opspilot.ai.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
public class JdbcDocumentRepositoryTests {

    // 使用固定且合法的64位哈希，方便每次测试前后清理数据。
    private static final String TEST_HASH = "a".repeat(64);
    private static final String SECOND_TEST_HASH ="b".repeat(64);

    @Autowired
    private DocumentRepository  repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("失败文档重新处理后恢复为处理中状态")
    void restartsFailedDocument() {
        UUID documentId = UUID.randomUUID();
        repository.create(documentId,"失败文档.txt",TEST_HASH);

        // 直接准备失败状态，让这个测试只关注 restart() 的行为。
        jdbcTemplate.update("""
            update knowledge_document
            set status = 'FAILED',
                chunk_count = 2,
                error_message = '向量生成失败'
            where id = ?
            """,
                documentId
        );

        repository.restart(documentId);

        assertThat(repository.findById(documentId))
                .hasValueSatisfying(document -> {
                    assertThat(document.status())
                            .isEqualTo(DocumentStatus.PROCESSING);
                    assertThat(document.chunkCount()).isZero();
                    assertThat(document.errorMessage()).isNull();
                });
    }

    @Test
    @DisplayName("文档处理失败时保存失败状态并截断错误信息")
    void marksDocumentFailed() {
        UUID documentId = UUID.randomUUID();
        repository.create(documentId, "失败文档.txt", TEST_HASH);
        String longErrorMessage = "错误".repeat(300);

        repository.markFailed(documentId, longErrorMessage);

        assertThat(repository.findById(documentId))
                .hasValueSatisfying(document -> {
                    assertThat(document.status())
                            .isEqualTo(DocumentStatus.FAILED);

                    // 数据库字段最大为500字符，不能直接保存无限长度的异常信息。
                    assertThat(document.errorMessage()).hasSize(500);
                });
    }

    @Test
    @DisplayName("查询文档列表时按照创建时间倒序排列")
    void findsDocumentsNewestFirst() {
        UUID oldDocumentId = UUID.randomUUID();
        UUID newDocumentId = UUID.randomUUID();

        repository.create(oldDocumentId, "旧文档.txt", TEST_HASH);
        repository.create(newDocumentId, "新文档.txt", SECOND_TEST_HASH);

        jdbcTemplate.update(
                "update knowledge_document set created_at = ? where id = ?",
                OffsetDateTime.parse("2026-08-14T10:00:00+08:00"),
                oldDocumentId
        );
        jdbcTemplate.update(
                "update knowledge_document set created_at = ? where id = ?",
                OffsetDateTime.parse("2026-08-15T10:00:00+08:00"),
                newDocumentId
        );

        List<UUID> testDocumentIds = repository.findAll().stream()
                .map(KnowledgeDocument::id)
                .filter(id -> id.equals(oldDocumentId) || id.equals(newDocumentId))
                .toList();

        assertThat(testDocumentIds)
                .containsExactly(newDocumentId, oldDocumentId);
    }

    @Test
    @DisplayName("根据文档ID删除记录")
    void deletesDocumentById() {
        UUID documentId = UUID.randomUUID();
        repository.create(documentId, "待删除文档.txt", TEST_HASH);

        repository.deleteById(documentId);

        assertThat(repository.findById(documentId)).isEmpty();
    }

    @BeforeEach
    @AfterEach
    void cleanTestData(){
        jdbcTemplate.update("""
            delete from knowledge_document
            where content_hash in (?, ?)
            """,
                TEST_HASH,
                SECOND_TEST_HASH
        );
    }

    @Test
    @DisplayName("创建文档后可以根据内容哈希查询")
    void createsAndFindsByHash() {
        UUID documentId = UUID.randomUUID();

        repository.create(documentId, "运维手册.txt", TEST_HASH);

        assertThat(repository.findByHash(TEST_HASH))
                .hasValueSatisfying(document -> {
                    assertThat(document.id()).isEqualTo(documentId);
                    assertThat(document.filename()).isEqualTo("运维手册.txt");
                    assertThat(document.status())
                            .isEqualTo(DocumentStatus.PROCESSING);
                });
    }

    @Test
    @DisplayName("文档处理完成后更新状态和切片数量")
    void marksDocumentReady() {
        UUID documentId = UUID.randomUUID();
        repository.create(documentId, "运维手册.txt", TEST_HASH);

        repository.markReady(documentId, 3);

        assertThat(repository.findById(documentId))
                .hasValueSatisfying(document -> {
                    assertThat(document.status())
                            .isEqualTo(DocumentStatus.READY);
                    assertThat(document.chunkCount()).isEqualTo(3);
                });
    }
}
