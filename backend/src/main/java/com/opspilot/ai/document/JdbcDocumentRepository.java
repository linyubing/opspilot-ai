package com.opspilot.ai.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private static final String COLUMNS = """
            id,
            filename,
            content_hash,
            status,
            chunk_count,
            error_message,
            created_at,
            updated_at
            """;
    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;


    /*
     * RowMapper 负责把数据库的一行数据转换成 KnowledgeDocument。
     * 集中定义可以避免每个查询重复编写字段映射。
     */
    private final RowMapper<KnowledgeDocument> rowMapper = (resultSet, rowNum) ->
            new KnowledgeDocument(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("filename"),
                    resultSet.getString("content_hash"),
                    DocumentStatus.valueOf(resultSet.getString("status")),
                    resultSet.getInt("chunk_count"),
                    resultSet.getString("error_message"),
                    resultSet.getObject("created_at", OffsetDateTime.class),
                    resultSet.getObject("updated_at", OffsetDateTime.class)
            );

    public JdbcDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<KnowledgeDocument> findByHash(String contentHash) {
        List<KnowledgeDocument> documents = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from knowledge_document
                        where content_hash = ?
                        """,
                rowMapper,
                contentHash
        );

        return documents.stream().findFirst();
    }

    @Override
    public Optional<KnowledgeDocument> findById(UUID id) {
        List<KnowledgeDocument> documents = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from knowledge_document
                        where id = ?
                        """,
                rowMapper,
                id
        );

        return documents.stream().findFirst();
    }

    @Override
    public KnowledgeDocument create(
            UUID id,
            String filename,
            String contentHash
    ) {
        jdbcTemplate.update("""
                insert into knowledge_document (
                    id,
                    filename,
                    content_hash,
                    status
                )
                values (?, ?, ?, ?)
                """,
                id,
                filename,
                contentHash,
                DocumentStatus.PROCESSING.name()
        );

        return findById(id).orElseThrow();
    }

    @Override
    public void markReady(UUID id, int chunkCount) {
        jdbcTemplate.update("""
                update knowledge_document
                set status = ?,
                    chunk_count = ?,
                    error_message = null,
                    updated_at = now()
                where id = ?
                """,
                DocumentStatus.READY.name(),
                chunkCount,
                id
        );
    }

    @Override
    public List<KnowledgeDocument> findAll() {
        return jdbcTemplate.query(
                "select " + COLUMNS + """
                    from knowledge_document
                    order by created_at desc
                    """,
                rowMapper
        );
    }

    @Override
    public void restart(UUID id) {
        jdbcTemplate.update("""
            update knowledge_document
            set status = ?,
                chunk_count = 0,
                error_message = null,
                updated_at = now()
            where id = ?
            """,
                DocumentStatus.PROCESSING.name(),
                id
        );
    }

    @Override
    public void markFailed(UUID id, String errorMessage) {
        jdbcTemplate.update("""
            update knowledge_document
            set status = ?,
                error_message = ?,
                updated_at = now()
            where id = ?
            """,
                DocumentStatus.FAILED.name(),
                limitError(errorMessage),
                id
        );
    }

    /**
     * 数据库只保存错误摘要，不保存可能非常长的完整堆栈。
     */
    private String limitError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }

        int endIndex = Math.min(
                errorMessage.length(),
                MAX_ERROR_LENGTH
        );
        return errorMessage.substring(0, endIndex);
    }

    @Override
    public void deleteById(UUID id) {
        jdbcTemplate.update(
                "delete from knowledge_document where id = ?",
                id
        );
    }
}
