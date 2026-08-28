package com.opspilot.ai.analysis.narrative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 使用 PostgreSQL 保存不可变黄金研究解读，并依靠唯一约束保证并发幂等。 */
@Repository
public class JdbcResearchNarrativeRepository
        implements ResearchNarrativeRepository {

    private static final String COLUMNS = """
            id,
            snapshot_id,
            summary,
            real_rate_analysis,
            dollar_index_analysis,
            risks,
            watch_list,
            disclaimer,
            model_name,
            prompt_version,
            prompt_hash,
            raw_response,
            created_at
            """;

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<StoredResearchNarrative> rowMapper =
            (resultSet, rowNum) -> new StoredResearchNarrative(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("snapshot_id", UUID.class),
                    new ResearchNarrativeContent(
                            resultSet.getString("summary"),
                            resultSet.getString("real_rate_analysis"),
                            resultSet.getString("dollar_index_analysis"),
                            readList(resultSet.getString("risks")),
                            readList(resultSet.getString("watch_list")),
                            resultSet.getString("disclaimer")
                    ),
                    resultSet.getString("model_name"),
                    resultSet.getString("prompt_version"),
                    resultSet.getString("prompt_hash"),
                    resultSet.getString("raw_response"),
                    resultSet.getObject("created_at", OffsetDateTime.class)
            );

    public JdbcResearchNarrativeRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredResearchNarrative> findByKey(
            UUID snapshotId,
            String modelName,
            String promptVersion
    ) {
        Objects.requireNonNull(snapshotId, "快照编号不能为空");
        Objects.requireNonNull(modelName, "模型名称不能为空");
        Objects.requireNonNull(promptVersion, "提示词版本不能为空");

        List<StoredResearchNarrative> records = jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_narrative
                        where snapshot_id = ?
                          and model_name = ?
                          and prompt_version = ?
                        """,
                rowMapper,
                snapshotId,
                modelName,
                promptVersion
        );
        return records.stream().findFirst();
    }

    @Override
    public SaveResearchNarrativeResult saveIfAbsent(
            StoredResearchNarrative candidate
    ) {
        Objects.requireNonNull(candidate, "待保存的研究解读不能为空");
        ResearchNarrativeContent content = candidate.content();

        int inserted = jdbcTemplate.update(
                """
                insert into gold_research_narrative (
                    id,
                    snapshot_id,
                    summary,
                    real_rate_analysis,
                    dollar_index_analysis,
                    risks,
                    watch_list,
                    disclaimer,
                    model_name,
                    prompt_version,
                    prompt_hash,
                    raw_response,
                    created_at
                )
                values (
                    ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                    ?, ?, ?, ?, ?, ?
                )
                on conflict (snapshot_id, model_name, prompt_version)
                do nothing
                """,
                candidate.id(),
                candidate.snapshotId(),
                content.summary(),
                content.realRateAnalysis(),
                content.dollarIndexAnalysis(),
                writeList(content.risks()),
                writeList(content.watchList()),
                content.disclaimer(),
                candidate.modelName(),
                candidate.promptVersion(),
                candidate.promptHash(),
                candidate.rawResponse(),
                candidate.createdAt()
        );

        StoredResearchNarrative record = findByKey(
                candidate.snapshotId(),
                candidate.modelName(),
                candidate.promptVersion()
        ).orElseThrow(() -> new IllegalStateException(
                "黄金研究解读保存后未能读取"
        ));

        return new SaveResearchNarrativeResult(record, inserted == 1);
    }

    @Override
    public List<StoredResearchNarrative> findBySnapshotId(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "快照编号不能为空");

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_narrative
                        where snapshot_id = ?
                        order by created_at desc, id desc
                        """,
                rowMapper,
                snapshotId
        );
    }

    @Override
    public Optional<StoredResearchNarrative> findLatestBySnapshotId(
            UUID snapshotId
    ) {
        Objects.requireNonNull(snapshotId, "快照编号不能为空");

        return jdbcTemplate.query(
                "select " + COLUMNS + """
                        from gold_research_narrative
                        where snapshot_id = ?
                        order by created_at desc, id desc
                        limit 1
                        """,
                rowMapper,
                snapshotId
        ).stream().findFirst();
    }

    /** JSONB 列只保存字符串列表，解析失败意味着正式历史数据已损坏。 */
    private List<String> readList(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new SQLException("研究解读 JSONB 列无法解析", exception);
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "研究解读列表无法转换为 JSON",
                    exception
            );
        }
    }
}
