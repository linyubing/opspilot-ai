package com.opspilot.ai.forecast.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 使用 PostgreSQL 保存可恢复的黄金回测任务和明细。 */
@Repository
public class JdbcBacktestRepository implements BacktestRepository {

    private static final String TASK_COLUMNS = """
            id, start_date, end_date, sample_count, model_name,
            prompt_version, rule_version, status, completed_count,
            hit_count, failed_count, last_error, created_at,
            started_at, completed_at
            """;

    private static final String CASE_COLUMNS = """
            id, backtest_id, as_of_date, snapshot, base_price,
            predicted_direction, reasoning, invalidation_conditions,
            target_date, target_price, actual_return, actual_direction,
            hit, model_name, prompt_version, prompt_hash, rule_version,
            raw_response, created_at
            """;

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private final RowMapper<BacktestTask> taskMapper = (rs, row) ->
            new BacktestTask(
                    rs.getObject("id", UUID.class),
                    rs.getObject("start_date", LocalDate.class),
                    rs.getObject("end_date", LocalDate.class),
                    rs.getInt("sample_count"),
                    rs.getString("model_name"),
                    rs.getString("prompt_version"),
                    rs.getString("rule_version"),
                    BacktestStatus.valueOf(
                            rs.getString("status").toUpperCase(Locale.ROOT)
                    ),
                    rs.getInt("completed_count"),
                    rs.getInt("hit_count"),
                    rs.getInt("failed_count"),
                    rs.getString("last_error"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("started_at", OffsetDateTime.class),
                    rs.getObject("completed_at", OffsetDateTime.class)
            );

    private final RowMapper<BacktestCase> caseMapper = (rs, row) ->
            new BacktestCase(
                    rs.getObject("id", UUID.class),
                    rs.getObject("backtest_id", UUID.class),
                    rs.getObject("as_of_date", LocalDate.class),
                    readSnapshot(rs.getString("snapshot")),
                    rs.getBigDecimal("base_price"),
                    direction(rs.getString("predicted_direction")),
                    rs.getString("reasoning"),
                    readList(rs.getString("invalidation_conditions")),
                    rs.getObject("target_date", LocalDate.class),
                    rs.getBigDecimal("target_price"),
                    rs.getBigDecimal("actual_return"),
                    direction(rs.getString("actual_direction")),
                    rs.getBoolean("hit"),
                    rs.getString("model_name"),
                    rs.getString("prompt_version"),
                    rs.getString("prompt_hash"),
                    rs.getString("rule_version"),
                    rs.getString("raw_response"),
                    rs.getObject("created_at", OffsetDateTime.class)
            );

    public JdbcBacktestRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public BacktestTask create(BacktestTask task) {
        jdbc.update("""
                insert into gold_forecast_backtest (
                    id, start_date, end_date, sample_count, model_name,
                    prompt_version, rule_version, status, completed_count,
                    hit_count, failed_count, last_error, created_at,
                    started_at, completed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(), task.startDate(), task.endDate(),
                task.sampleCount(), task.modelName(), task.promptVersion(),
                task.ruleVersion(), lower(task.status()),
                task.completedCount(), task.hitCount(), task.failedCount(),
                task.lastError(), task.createdAt(), task.startedAt(),
                task.completedAt()
        );
        return findTask(task.id()).orElseThrow();
    }

    @Override
    public Optional<BacktestTask> findTask(UUID id) {
        return jdbc.query(
                "select " + TASK_COLUMNS
                        + "from gold_forecast_backtest where id = ?",
                taskMapper,
                id
        ).stream().findFirst();
    }

    @Override
    public List<BacktestCase> findCases(UUID id, int limit) {
        checkLimit(limit);
        return jdbc.query(
                "select " + CASE_COLUMNS + """
                        from gold_forecast_backtest_case
                        where backtest_id = ?
                        order by as_of_date desc, id desc
                        limit ?
                        """,
                caseMapper,
                id,
                limit
        );
    }

    @Override
    public Set<LocalDate> findDoneDates(UUID id) {
        List<LocalDate> dates = jdbc.queryForList(
                """
                select as_of_date
                from gold_forecast_backtest_case
                where backtest_id = ?
                order by as_of_date
                """,
                LocalDate.class,
                id
        );
        return new LinkedHashSet<>(dates);
    }

    @Override
    public boolean start(UUID id, OffsetDateTime time) {
        return jdbc.update("""
                update gold_forecast_backtest
                set status = 'running',
                    started_at = coalesce(started_at, ?),
                    completed_at = null,
                    last_error = null
                where id = ? and status in ('created', 'failed')
                """, time, id) == 1;
    }

    @Override
    @Transactional
    public boolean saveCase(BacktestCase item) {
        int inserted = jdbc.update("""
                insert into gold_forecast_backtest_case (
                    id, backtest_id, as_of_date, snapshot, base_price,
                    predicted_direction, reasoning, invalidation_conditions,
                    target_date, target_price, actual_return, actual_direction,
                    hit, model_name, prompt_version, prompt_hash, rule_version,
                    raw_response, created_at
                ) values (
                    ?, ?, ?, cast(? as jsonb), ?, ?, ?, cast(? as jsonb),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                on conflict (backtest_id, as_of_date) do nothing
                """,
                item.id(), item.backtestId(), item.asOfDate(),
                write(item.snapshot()), item.basePrice(),
                lower(item.predictedDirection()), item.reasoning(),
                write(item.invalidationConditions()), item.targetDate(),
                item.targetPrice(), item.actualReturn(),
                lower(item.actualDirection()), item.hit(), item.modelName(),
                item.promptVersion(), item.promptHash(), item.ruleVersion(),
                item.rawResponse(), item.createdAt()
        );

        if (inserted == 1) {
            // 只有首次插入明细才累计任务进度，保证恢复执行时幂等。
            jdbc.update("""
                    update gold_forecast_backtest
                    set completed_count = completed_count + 1,
                        hit_count = hit_count + ?
                    where id = ?
                    """, item.hit() ? 1 : 0, item.backtestId());
        }
        return inserted == 1;
    }

    @Override
    public void fail(UUID id, String error) {
        jdbc.update("""
                update gold_forecast_backtest
                set status = 'failed', last_error = ?
                where id = ?
                """, error, id);
    }

    @Override
    public void complete(UUID id, OffsetDateTime time) {
        jdbc.update("""
                update gold_forecast_backtest
                set status = 'completed', completed_at = ?, last_error = null
                where id = ? and status = 'running'
                """, time, id);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("回测 JSON 无法序列化", exception);
        }
    }

    private GoldResearchSnapshot readSnapshot(String value)
            throws SQLException {
        try {
            return json.readValue(value, GoldResearchSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("回测快照 JSON 无法解析", exception);
        }
    }

    private List<String> readList(String value) throws SQLException {
        try {
            return json.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("回测列表 JSON 无法解析", exception);
        }
    }

    private ForecastDirection direction(String value) {
        return ForecastDirection.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > 120) {
            throw new IllegalArgumentException("limit 必须在 1 到 120 之间");
        }
    }
}
