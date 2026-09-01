package com.opspilot.ai.forecast.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 基于JDBC的模型实验数据访问实现。 */
@Repository
public class JdbcModelExperimentRepository implements ModelExperimentRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcModelExperimentRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(ModelExperiment experiment) {
        jdbc.update("""
                insert into gold_model_experiment (
                    id, horizon, feature_version, label_version, split_version,
                    dataset_hash, parameter_json, data_start, data_end,
                    train_start, validation_start, validation_end,
                    holdout_start, holdout_end, validation_samples, holdout_samples,
                    status, git_commit, failure_message, created_at, started_at, completed_at
                ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                experiment.id(), experiment.horizon(),
                experiment.featureVersion(), experiment.labelVersion(),
                experiment.splitVersion(), experiment.datasetHash(),
                toJson(experiment.parameters()),
                experiment.dataStart(), experiment.dataEnd(),
                experiment.trainStart(), experiment.validationStart(),
                experiment.validationEnd(), experiment.holdoutStart(),
                experiment.holdoutEnd(), experiment.validationSamples(),
                experiment.holdoutSamples(), experiment.status().name(),
                experiment.gitCommit(), experiment.failureMessage(),
                experiment.createdAt(), experiment.startedAt(),
                experiment.completedAt()
        );
    }

    @Override
    public void markRunning(UUID id, OffsetDateTime startedAt) {
        jdbc.update("""
                update gold_model_experiment
                set status = 'RUNNING', started_at = ?
                where id = ?
                """, startedAt, id);
    }

    @Override
    @Transactional
    public void complete(
            UUID id,
            ModelExperiment completed,
            List<ModelExperimentMetric> metrics
    ) {
        jdbc.update("""
                update gold_model_experiment
                set status = 'COMPLETED', completed_at = ?,
                    holdout_samples = ?, holdout_start = ?, holdout_end = ?
                where id = ?
                """,
                completed.completedAt(),
                completed.holdoutSamples(),
                completed.holdoutStart(),
                completed.holdoutEnd(),
                id
        );
        for (ModelExperimentMetric metric : metrics) {
            jdbc.update("""
                    insert into gold_model_experiment_metric (
                        experiment_id, model_type, sample_count, covered_count,
                        coverage, accuracy, balanced_accuracy, brier_score, log_loss,
                        recalls, confusion_matrix
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """,
                    metric.experimentId(), metric.modelType().name(),
                    metric.sampleCount(), metric.coveredCount(),
                    metric.coverage(), metric.accuracy(),
                    metric.balancedAccuracy(), metric.brierScore(),
                    metric.logLoss(), toJson(metric.recalls()),
                    toJson(metric.confusionMatrix())
            );
        }
    }

    @Override
    public void fail(UUID id, String message, OffsetDateTime completedAt) {
        jdbc.update("""
                update gold_model_experiment
                set status = 'FAILED', failure_message = ?, completed_at = ?
                where id = ?
                """, message, completedAt, id);
    }

    @Override
    public Optional<ModelExperiment> findById(UUID id) {
        List<ModelExperiment> results = jdbc.query(
                "select * from gold_model_experiment where id = ?",
                experimentRowMapper(),
                id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<ModelExperiment> findRecent(int limit) {
        return jdbc.query(
                "select * from gold_model_experiment order by created_at desc limit ?",
                experimentRowMapper(),
                limit
        );
    }

    @Override
    public List<ModelExperimentMetric> findMetrics(UUID experimentId) {
        return jdbc.query(
                "select * from gold_model_experiment_metric where experiment_id = ?",
                metricRowMapper(),
                experimentId
        );
    }

    private RowMapper<ModelExperiment> experimentRowMapper() {
        return (rs, rowNum) -> new ModelExperiment(
                rs.getObject("id", UUID.class),
                rs.getString("horizon"),
                rs.getString("feature_version"),
                rs.getString("label_version"),
                rs.getString("split_version"),
                rs.getString("dataset_hash"),
                parseJson(rs.getString("parameter_json")),
                rs.getDate("data_start").toLocalDate(),
                rs.getDate("data_end").toLocalDate(),
                rs.getDate("train_start").toLocalDate(),
                rs.getDate("validation_start").toLocalDate(),
                rs.getDate("validation_end").toLocalDate(),
                rs.getDate("holdout_start").toLocalDate(),
                rs.getDate("holdout_end").toLocalDate(),
                rs.getInt("validation_samples"),
                rs.getInt("holdout_samples"),
                ModelExperimentStatus.valueOf(rs.getString("status")),
                rs.getString("git_commit"),
                rs.getString("failure_message"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class)
        );
    }

    @SuppressWarnings("unchecked")
    private RowMapper<ModelExperimentMetric> metricRowMapper() {
        return (rs, rowNum) -> new ModelExperimentMetric(
                rs.getObject("experiment_id", UUID.class),
                ModelType.valueOf(rs.getString("model_type")),
                rs.getInt("sample_count"),
                rs.getInt("covered_count"),
                rs.getBigDecimal("coverage"),
                rs.getBigDecimal("accuracy"),
                rs.getBigDecimal("balanced_accuracy"),
                rs.getBigDecimal("brier_score"),
                rs.getBigDecimal("log_loss"),
                parseJsonMap(rs.getString("recalls")),
                parseJsonMapOfMaps(rs.getString("confusion_matrix"))
        );
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
    }

    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON反序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON反序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Integer>> parseJsonMapOfMaps(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON反序列化失败", e);
        }
    }
}
