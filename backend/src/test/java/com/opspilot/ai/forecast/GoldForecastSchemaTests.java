package com.opspilot.ai.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证黄金方向预测表的字段和数据库约束。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class GoldForecastSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("创建包含二十个字段的方向预测表")
    void createsTwentyColumnTable() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'gold_direction_forecast'
                """, Long.class);
        assertThat(count).isEqualTo(20L);
    }

    @Test
    @DisplayName("创建 JSONB、快照外键和四字段幂等唯一索引")
    void createsPersistenceConstraints() {
        Long jsonColumns = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'gold_direction_forecast'
                  and column_name = 'invalidation_conditions'
                  and data_type = 'jsonb'
                """, Long.class);
        Long foreignKeys = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.table_constraints
                where constraint_schema = 'public'
                  and table_name = 'gold_direction_forecast'
                  and constraint_type = 'FOREIGN KEY'
                """, Long.class);
        Long uniqueIndexes = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public'
                  and tablename = 'gold_direction_forecast'
                  and indexdef like '%(snapshot_id, model_name, prompt_version, forecast_rule_version)%'
                """, Long.class);

        assertThat(jsonColumns).isEqualTo(1L);
        assertThat(foreignKeys).isEqualTo(1L);
        assertThat(uniqueIndexes).isEqualTo(1L);
    }
}
