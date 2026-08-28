package com.opspilot.ai.forecast.backtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金回测任务和明细表由 Flyway 正确创建。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class BacktestSchemaTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("创建独立的回测任务表和明细表")
    void createsBacktestTables() {
        Long count = jdbc.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public'
                  and table_name in (
                      'gold_forecast_backtest',
                      'gold_forecast_backtest_case'
                  )
                """, Long.class);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("回测明细使用 JSONB 并按任务和日期保证唯一")
    void createsCaseConstraints() {
        Long jsonColumns = jdbc.queryForObject("""
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'gold_forecast_backtest_case'
                  and data_type = 'jsonb'
                """, Long.class);
        Long uniqueIndexes = jdbc.queryForObject("""
                select count(*)
                from pg_indexes
                where schemaname = 'public'
                  and tablename = 'gold_forecast_backtest_case'
                  and indexdef like '%(backtest_id, as_of_date)%'
                """, Long.class);

        assertThat(jsonColumns).isEqualTo(2L);
        assertThat(uniqueIndexes).isEqualTo(1L);
    }
}
