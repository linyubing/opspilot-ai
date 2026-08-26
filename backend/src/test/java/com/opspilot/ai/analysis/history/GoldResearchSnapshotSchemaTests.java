package com.opspilot.ai.analysis.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class GoldResearchSnapshotSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway 创建黄金研究快照表")
    void createsSnapshotTable() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name = 'gold_research_snapshot'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("分析日期和规则版本具有唯一约束")
    void createsIdempotencyConstraint() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'gold_research_snapshot'
                  and indexdef like '%(analysis_date, rule_version)%'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }
}
