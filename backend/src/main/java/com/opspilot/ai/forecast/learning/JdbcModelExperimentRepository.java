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
                    id, comparison_id, horizon, feature_version, label_version, split_version,
                    dataset_hash, feature_profile, parameter_json, data_start, data_end,
                    train_start, validation_start, validation_end,
                    holdout_start, holdout_end, validation_samples, holdout_samples,
                    status, git_commit, failure_message, created_at, started_at, completed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                experiment.id(), experiment.comparisonId(),
                experiment.horizon(),
                experiment.featureVersion(), experiment.labelVersion(),
                experiment.splitVersion(), experiment.datasetHash(),
                experiment.featureProfile().name(),
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
        int updated = jdbc.update("""
                update gold_model_experiment
                set status = 'COMPLETED', completed_at = ?,
                    train_start = ?, validation_start = ?, validation_end = ?,
                    holdout_start = ?, holdout_end = ?,
                    validation_samples = ?, holdout_samples = ?
                where id = ?
                """,
                completed.completedAt(),
                completed.trainStart(),
                completed.validationStart(),
                completed.validationEnd(),
                completed.holdoutStart(),
                completed.holdoutEnd(),
                completed.validationSamples(),
                completed.holdoutSamples(),
                id
        );
        if (updated == 0) {
            throw new ModelExperimentNotFoundException("实验不存在，编号=" + id);
        }
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
    @Transactional
    public void createComparison(List<ModelExperiment> experiments) {
        if (experiments.size() != 3) {
            throw new IllegalArgumentException("批次实验数量必须为3，实际=" + experiments.size());
        }
        for (ModelExperiment experiment : experiments) {
            create(experiment);
        }
    }

    @Override
    public void markComparisonRunning(UUID comparisonId, OffsetDateTime startedAt) {
        int updated = jdbc.update("""
                update gold_model_experiment
                set status = 'RUNNING', started_at = ?
                where comparison_id = ?
                """, startedAt, comparisonId);
        if (updated != 3) {
            throw new ModelExperimentException(
                    "批量标记运行状态失败：预期3条，实际更新" + updated + "条，comparisonId=" + comparisonId
            );
        }
    }

    @Override
    @Transactional
    public void completeComparison(
            UUID comparisonId,
            List<ModelExperiment> experiments,
            List<ModelExperimentMetric> metrics
    ) {
        if (experiments.size() != 3) {
            throw new IllegalArgumentException("批次实验数量必须为3，实际=" + experiments.size());
        }
        if (metrics.size() != 9) {
            throw new IllegalArgumentException("批次指标数量必须为9，实际=" + metrics.size());
        }

        validateComparison(comparisonId, experiments, metrics);

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

        int updated = jdbc.update("""
                update gold_model_experiment
                set status = 'COMPLETED', completed_at = coalesce(?, now())
                where comparison_id = ?
                """, experiments.getFirst().completedAt(), comparisonId);
        if (updated != 3) {
            throw new ModelExperimentException(
                    "批量完成状态更新失败：预期3条，实际更新" + updated + "条，comparisonId=" + comparisonId
            );
        }
    }

    private void validateComparison(
            UUID comparisonId,
            List<ModelExperiment> experiments,
            List<ModelExperimentMetric> metrics
    ) {
        ModelExperiment first = experiments.getFirst();
        java.util.Set<FeatureProfile> profiles = new java.util.HashSet<>();
        java.util.Set<UUID> experimentIds = new java.util.HashSet<>();
        for (ModelExperiment exp : experiments) {
            profiles.add(exp.featureProfile());
            experimentIds.add(exp.id());
            if (!comparisonId.equals(exp.comparisonId())) {
                throw new IllegalArgumentException(
                        "实验comparisonId不匹配：期望=" + comparisonId + "，实际=" + exp.comparisonId()
                );
            }
            if (!first.datasetHash().equals(exp.datasetHash())) {
                throw new IllegalArgumentException("同一批次的数据集指纹必须一致");
            }
            if (!first.horizon().equals(exp.horizon())) {
                throw new IllegalArgumentException("同一批次的预测周期必须一致");
            }
            if (!first.trainStart().equals(exp.trainStart())
                    || !first.validationStart().equals(exp.validationStart())
                    || !first.validationEnd().equals(exp.validationEnd())
                    || !first.holdoutStart().equals(exp.holdoutStart())
                    || !first.holdoutEnd().equals(exp.holdoutEnd())) {
                throw new IllegalArgumentException("同一批次的时间分区必须一致");
            }
        }
        if (!profiles.equals(java.util.Set.of(
                FeatureProfile.BASE_16, FeatureProfile.OHLC_20, FeatureProfile.ALL_36))) {
            throw new IllegalArgumentException("批次缺少必要的特征组合: " + profiles);
        }

        Map<UUID, java.util.Set<ModelType>> typesByExperiment = new java.util.HashMap<>();
        for (ModelExperimentMetric metric : metrics) {
            if (!experimentIds.contains(metric.experimentId())) {
                throw new IllegalArgumentException("指标不属于当前批次实验，experimentId=" + metric.experimentId());
            }
            typesByExperiment.computeIfAbsent(metric.experimentId(), ignored -> new java.util.HashSet<>())
                    .add(metric.modelType());
        }
        java.util.Set<ModelType> requiredTypes = java.util.Set.of(
                ModelType.MAJORITY, ModelType.LOGISTIC, ModelType.XGBOOST);
        for (UUID experimentId : experimentIds) {
            if (!requiredTypes.equals(typesByExperiment.get(experimentId))) {
                throw new IllegalArgumentException("每条实验必须包含三种模型指标，experimentId=" + experimentId);
            }
        }
    }

    @Override
    public void failComparison(
            UUID comparisonId,
            String message,
            OffsetDateTime completedAt
    ) {
        int updated = jdbc.update("""
                update gold_model_experiment
                set status = 'FAILED', failure_message = ?, completed_at = ?
                where comparison_id = ?
                """, message, completedAt, comparisonId);
        if (updated != 3) {
            throw new ModelExperimentException(
                    "批量失败状态更新失败：预期3条，实际更新" + updated + "条，comparisonId=" + comparisonId
            );
        }
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
                rs.getObject("comparison_id", UUID.class),
                rs.getString("horizon"),
                rs.getString("feature_version"),
                rs.getString("label_version"),
                rs.getString("split_version"),
                rs.getString("dataset_hash"),
                rs.getString("feature_profile") != null
                        ? FeatureProfile.valueOf(rs.getString("feature_profile"))
                        : FeatureProfile.ALL_36,
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
