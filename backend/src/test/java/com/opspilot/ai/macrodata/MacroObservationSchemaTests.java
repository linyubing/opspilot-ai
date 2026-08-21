package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class MacroObservationSchemaTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("宏观观测表包含版本管理需要的全部字段")
    void hasRequiredColumns() {
        List<String> columnNames = jdbcTemplate.queryForList(
                """
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'macro_observation'
                """,
                String.class
        );

        assertThat(columnNames).containsExactlyInAnyOrder(
                "id",
                "series_id",
                "observation_date",
                "observation_value",
                "unit",
                "provider",
                "collected_at",
                "superseded_at"
        );
    }

    @Test
    @DisplayName("每个序列和观测日期最多只有一个当前版本")
    void hasUniqueCurrentVersionIndex() {
        String indexDefinition = jdbcTemplate.queryForObject(
                """
                select indexdef
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'macro_observation'
                  and indexname = 'uk_macro_observation_current'
                """,
                String.class
        );

        assertThat(indexDefinition)
                .containsIgnoringCase("unique")
                .containsIgnoringCase("superseded_at is null");
    }

    @Test
    @DisplayName("旧版本的关闭时间不能早于采集时间")
    void rejectsInvalidVersionTimeRange() {
        OffsetDateTime collectedAt =
                OffsetDateTime.parse("2026-08-20T03:00:00Z");
        OffsetDateTime invalidSupersededAt =
                OffsetDateTime.parse("2026-08-20T02:59:59Z");

        /*
         * 固定值只用于验证数据库版本时间约束，
         * 不代表 FRED 的真实实时利率。
         */
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                insert into macro_observation (
                    id,
                    series_id,
                    observation_date,
                    observation_value,
                    unit,
                    provider,
                    collected_at,
                    superseded_at
                ) values (?, ?, date '2026-08-19', 1.850000, 'percent',
                          'schema_test', ?, ?)
                """,
                UUID.randomUUID(),
                "DFII10_SCHEMA_TEST",
                collectedAt,
                invalidSupersededAt
        )).isInstanceOf(DataIntegrityViolationException.class);
    }
}
