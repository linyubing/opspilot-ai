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
                  and indexdef like '%(analysis_date, research_version)%'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("双因子快照包含研究版本和完整美元指数列")
    void addsDollarIndexSnapshotColumns() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'gold_research_snapshot'
                  and column_name in (
                      'research_version',
                      'real_rate_rule_version',
                      'latest_dollar_index_date',
                      'dollar_index',
                      'dollar_index_return_1',
                      'dollar_index_return_5',
                      'dollar_index_return_20',
                      'dollar_index_collected_at',
                      'dollar_index_status',
                      'dollar_index_rule_version',
                      'dollar_index_explanation'
                  )
                """, Long.class);

        assertThat(count).isEqualTo(11L);
    }
}
