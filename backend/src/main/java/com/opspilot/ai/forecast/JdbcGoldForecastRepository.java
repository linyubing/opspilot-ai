package com.opspilot.ai.forecast;

import java.sql.SQLException;
import java.sql.ResultSet;
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
            hit, resolved_at, created_at,
            promised_target_date, target_start, target_end, timing_rule, signal_status, reported_model
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
                    rs.getObject("created_at", OffsetDateTime.class), readTiming(rs),
                    signal(rs.getString("signal_status")), rs.getString("reported_model")
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
    @org.springframework.transaction.annotation.Transactional
    public SaveGoldForecastResult saveWithCheck(StoredGoldDirectionForecast candidate, GoldResolutionCheck check) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(check);
        if (!candidate.id().equals(check.forecastId())
                || check.code() != GoldResolutionCheck.Code.GENERATION_BASE_CHECKED
                || check.baseCheck() == null || !check.baseCheck().matched()
                || check.baseCheck().input() == null
                || check.baseCheck().input().day() == null
                || !candidate.baseDate().equals(check.baseCheck().input().day().priceDate())
                || candidate.basePrice().compareTo(check.baseCheck().input().day().close()) != 0
                || !check.checkedAt().toInstant().equals(check.baseCheck().checkedAt())
                || check.targetCheck() != null || check.checkedAt().isAfter(candidate.createdAt())) {
            throw new IllegalArgumentException("预测与生成前基准依据不匹配");
        }
        var saved = saveIfAbsent(candidate);
        // 唯一键竞争的失败方不能把自己的后查依据附到胜出方原预测上。
        if (saved.created()) recordCheck(check);
        return saved;
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
                    hit, resolved_at, created_at,
                    promised_target_date, target_start, target_end, timing_rule, signal_status, reported_model
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (snapshot_id, model_name, prompt_version, forecast_rule_version)
                do nothing
                """, c.id(), c.snapshotId(), c.baseDate(), c.basePrice(), lower(c.predictedDirection()),
                c.reasoning(), writeList(c.invalidationConditions()), c.modelName(), c.promptVersion(),
                c.promptHash(), c.forecastRuleVersion(), c.rawResponse(), lower(c.status()), c.targetDate(),
                c.targetPrice(), c.actualReturn(), lower(c.actualDirection()), c.hit(), c.resolvedAt(), c.createdAt(),
                c.timing() == null ? null : c.timing().targetDate(),
                c.timing() == null ? null : OffsetDateTime.ofInstant(c.timing().start(), java.time.ZoneOffset.UTC),
                c.timing() == null ? null : OffsetDateTime.ofInstant(c.timing().end(), java.time.ZoneOffset.UTC),
                c.timing() == null ? null : c.timing().ruleVersion(), lower(c.signal()), c.reportedModel());
        StoredGoldDirectionForecast stored = findByKey(c.snapshotId(), c.modelName(),
                c.promptVersion(), c.forecastRuleVersion())
                .orElseThrow(() -> new IllegalStateException("黄金方向预测保存后未能读取"));
        return new SaveGoldForecastResult(stored, inserted == 1);
    }

    @Override public List<StoredGoldDirectionForecast> findPending(int limit) {
        validateLimit(limit);
        return jdbcTemplate.query("select " + COLUMNS + """
                from gold_direction_forecast where status = 'pending'
                order by last_scanned_at asc nulls first, created_at asc, id asc limit ?
                """, rowMapper, limit);
    }
    @Override public void markScanned(UUID id) {
        Objects.requireNonNull(id, "预测编号不能为空");
        jdbcTemplate.update("""
                update gold_direction_forecast set last_scanned_at = clock_timestamp()
                where id = ? and status = 'pending'
                """, id);
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

    @Override public void recordCheck(GoldResolutionCheck check) {
        Objects.requireNonNull(check, "结算检查不能为空");
        try {
            jdbcTemplate.update("""
                    insert into gold_forecast_check(id, forecast_id, checked_at, code, details)
                    values (?, ?, ?, ?, cast(? as jsonb))
                    """, check.id(), check.forecastId(), check.checkedAt(),
                    lower(check.code()), objectMapper.writeValueAsString(check));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("结算检查无法转换为JSON", exception);
        }
    }

    @Override public List<GoldResolutionCheck> findChecks(UUID forecastId, int limit) {
        Objects.requireNonNull(forecastId, "预测编号不能为空");
        validateLimit(limit);
        return jdbcTemplate.query("""
                select details from gold_forecast_check where forecast_id = ?
                order by checked_at desc, id desc limit ?
                """, (rs, row) -> {
                    try { return objectMapper.readValue(rs.getString(1), GoldResolutionCheck.class); }
                    catch (JsonProcessingException exception) { throw new SQLException("结算检查JSON无效", exception); }
                }, forecastId, limit);
    }
    private GoldForecastTiming readTiming(ResultSet rs) throws SQLException {
        LocalDate date = rs.getObject("promised_target_date", LocalDate.class);
        OffsetDateTime start = rs.getObject("target_start", OffsetDateTime.class);
        OffsetDateTime end = rs.getObject("target_end", OffsetDateTime.class);
        String rule = rs.getString("timing_rule");
        if (date == null && start == null && end == null && rule == null) return null;
        return new GoldForecastTiming(date, start == null ? null : start.toInstant(),
                end == null ? null : end.toInstant(), rule);
    }
    private void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必须在 1 到 100 之间");
    }
    private ForecastDirection direction(String value) {
        return value == null ? null : ForecastDirection.valueOf(value.toUpperCase(Locale.ROOT));
    }
    private ForecastSignal signal(String value) {
        return value == null ? null : ForecastSignal.valueOf(value.toUpperCase(Locale.ROOT));
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
