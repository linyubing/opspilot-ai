package com.opspilot.ai.forecast;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 使用 PostgreSQL 保存不可变黄金预测，并依靠唯一约束保证并发幂等。 */
@Repository
public class JdbcGoldForecastRepository implements GoldForecastRepository {

    private static final String COLUMNS = """
            id, snapshot_id, base_date, base_price, predicted_direction,
            reasoning, invalidation_conditions, model_name, prompt_version,
            prompt_hash, forecast_rule_version, raw_response, status,
            target_date, target_price, actual_return, actual_direction,
            hit, resolved_at, created_at
            """;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<StoredGoldDirectionForecast> rowMapper = (rs, rowNum) ->
            new StoredGoldDirectionForecast(
                    rs.getObject("id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                    rs.getObject("base_date", LocalDate.class), rs.getBigDecimal("base_price"),
                    direction(rs.getString("predicted_direction")), rs.getString("reasoning"),
                    readList(rs.getString("invalidation_conditions")), rs.getString("model_name"),
                    rs.getString("prompt_version"), rs.getString("prompt_hash"),
                    rs.getString("forecast_rule_version"), rs.getString("raw_response"),
                    ForecastStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                    rs.getObject("target_date", LocalDate.class), rs.getBigDecimal("target_price"),
                    rs.getBigDecimal("actual_return"), direction(rs.getString("actual_direction")),
                    rs.getObject("hit", Boolean.class), rs.getObject("resolved_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class)
            );

    public JdbcGoldForecastRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredGoldDirectionForecast> findByKey(UUID snapshotId, String modelName,
            String promptVersion, String ruleVersion) {
        return jdbcTemplate.query("select " + COLUMNS + """
                from gold_direction_forecast
                where snapshot_id = ? and model_name = ?
                  and prompt_version = ? and forecast_rule_version = ?
                """, rowMapper, snapshotId, modelName, promptVersion, ruleVersion)
                .stream().findFirst();
    }

    @Override
    public SaveGoldForecastResult saveIfAbsent(StoredGoldDirectionForecast c) {
        Objects.requireNonNull(c, "待保存的黄金预测不能为空");
        int inserted = jdbcTemplate.update("""
                insert into gold_direction_forecast (
                    id, snapshot_id, base_date, base_price, predicted_direction,
                    reasoning, invalidation_conditions, model_name, prompt_version,
                    prompt_hash, forecast_rule_version, raw_response, status,
                    target_date, target_price, actual_return, actual_direction,
                    hit, resolved_at, created_at
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (snapshot_id, model_name, prompt_version, forecast_rule_version)
                do nothing
                """, c.id(), c.snapshotId(), c.baseDate(), c.basePrice(), lower(c.predictedDirection()),
                c.reasoning(), writeList(c.invalidationConditions()), c.modelName(), c.promptVersion(),
                c.promptHash(), c.forecastRuleVersion(), c.rawResponse(), lower(c.status()), c.targetDate(),
                c.targetPrice(), c.actualReturn(), lower(c.actualDirection()), c.hit(), c.resolvedAt(), c.createdAt());
        StoredGoldDirectionForecast stored = findByKey(c.snapshotId(), c.modelName(),
                c.promptVersion(), c.forecastRuleVersion())
                .orElseThrow(() -> new IllegalStateException("黄金方向预测保存后未能读取"));
        return new SaveGoldForecastResult(stored, inserted == 1);
    }

    @Override public List<StoredGoldDirectionForecast> findPending(int limit) {
        validateLimit(limit);
        return jdbcTemplate.query("select " + COLUMNS + """
                from gold_direction_forecast where status = 'pending'
                order by created_at asc, id asc limit ?
                """, rowMapper, limit);
    }
    @Override public List<StoredGoldDirectionForecast> findRecent(int limit) {
        validateLimit(limit);
        return jdbcTemplate.query("select " + COLUMNS + """
                from gold_direction_forecast order by created_at desc, id desc limit ?
                """, rowMapper, limit);
    }
    @Override public List<StoredGoldDirectionForecast> findAllForEvaluation() {
        return jdbcTemplate.query("select " + COLUMNS + """
                from gold_direction_forecast order by created_at desc, id desc
                """, rowMapper);
    }

    @Override
    public Optional<StoredGoldDirectionForecast> findLatestBySnapshotId(
            UUID snapshotId
    ) {
        Objects.requireNonNull(snapshotId, "快照编号不能为空");
        return jdbcTemplate.query("select " + COLUMNS + """
                from gold_direction_forecast
                where snapshot_id = ?
                order by created_at desc, id desc
                limit 1
                """, rowMapper, snapshotId).stream().findFirst();
    }

    @Override
    public StoredGoldDirectionForecast resolve(UUID id, ForecastResolution r) {
        jdbcTemplate.update("""
                update gold_direction_forecast set
                    status = 'resolved', target_date = ?, target_price = ?,
                    actual_return = ?, actual_direction = ?, hit = ?, resolved_at = ?
                where id = ? and status = 'pending'
                """, r.targetDate(), r.targetPrice(), r.actualReturn(), lower(r.actualDirection()),
                r.hit(), r.resolvedAt(), id);
        return findById(id).orElseThrow(() -> new IllegalArgumentException("黄金方向预测不存在"));
    }

    private Optional<StoredGoldDirectionForecast> findById(UUID id) {
        return jdbcTemplate.query("select " + COLUMNS
                + "from gold_direction_forecast where id = ?", rowMapper, id).stream().findFirst();
    }
    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
    }
    private ForecastDirection direction(String value) {
        return value == null ? null : ForecastDirection.valueOf(value.toUpperCase(Locale.ROOT));
    }
    private String lower(Enum<?> value) { return value == null ? null : value.name().toLowerCase(Locale.ROOT); }
    private List<String> readList(String json) throws SQLException {
        try { return objectMapper.readValue(json, STRING_LIST); }
        catch (JsonProcessingException e) { throw new SQLException("预测失效条件 JSONB 无法解析", e); }
    }
    private String writeList(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("预测失效条件无法转换为 JSON", e); }
    }
}
