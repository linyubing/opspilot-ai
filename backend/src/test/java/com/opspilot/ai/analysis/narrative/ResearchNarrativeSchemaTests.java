package com.opspilot.ai.analysis.narrative;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class ResearchNarrativeSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsNarrativeTableWithThirteenColumns() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'gold_research_narrative'
                """, Long.class);

        assertThat(count).isEqualTo(13L);
    }

    @Test
    void createsIdempotencyConstraint() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'gold_research_narrative'
                  and indexdef like '%(snapshot_id, model_name, prompt_version)%'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }

    @Test
    void createsJsonListsAndSnapshotForeignKey() {
        Long jsonColumns = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'gold_research_narrative'
                  and column_name in ('risks', 'watch_list')
                  and data_type = 'jsonb'
                """, Long.class);
        Long foreignKeys = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where constraint_schema = 'public'
                  and table_name = 'gold_research_narrative'
                  and constraint_type = 'FOREIGN KEY'
                """, Long.class);

        assertThat(jsonColumns).isEqualTo(2L);
        assertThat(foreignKeys).isEqualTo(1L);
    }

    @Test
    void validatesPromptHashFormatInDatabase() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.table_constraints
                where constraint_schema = 'public'
                  and table_name = 'gold_research_narrative'
                  and constraint_name = 'ck_gold_research_narrative_prompt_hash'
                  and constraint_type = 'CHECK'
                """, Long.class);

        assertThat(count).isEqualTo(1L);
    }
}
