package com.opspilot.ai.document;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
public class DocumentSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createKnowledgeDocumentTable(){
        /*
         * information_schema 是数据库提供的结构信息视图。
         * 这里验证 Flyway 最终确实创建了业务表，而不是只检查配置文件。
         */
        Long tableCount = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'knowledge_document'
                """, Long.class);

        assertThat(tableCount).isEqualTo(1L);
    }
}
