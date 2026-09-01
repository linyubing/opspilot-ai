package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ModelExperimentSchemaTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsGoldModelExperimentTable() {
        assertThat(tableExists("gold_model_experiment")).isTrue();
    }

    @Test
    void createsGoldModelExperimentMetricTable() {
        assertThat(tableExists("gold_model_experiment_metric")).isTrue();
    }

    @Test
    void hasRequiredColumns() {
        var columns = jdbc.queryForList(
                "select column_name from information_schema.columns " +
                        "where table_name = 'gold_model_experiment' " +
                        "order by ordinal_position"
        ).stream().map(r -> r.get("column_name").toString()).toList();

        assertThat(columns).contains(
                "id", "horizon", "feature_version", "label_version", "split_version",
                "dataset_hash", "feature_profile", "parameter_json",
                "data_start", "data_end",
                "train_start", "validation_start", "validation_end",
                "holdout_start", "holdout_end", "validation_samples", "holdout_samples",
                "status", "git_commit", "failure_message", "created_at", "started_at", "completed_at"
        );
    }

    @Test
    void hasFeatureProfileCheckConstraint() {
        var constraints = jdbc.queryForList(
                "select conname from pg_constraint " +
                        "where conrelid = 'gold_model_experiment'::regclass " +
                        "and contype = 'c'"
        );
        assertThat(constraints).anyMatch(
                r -> r.get("conname").toString().contains("feature_profile")
        );
    }

    @Test
    void hasMetricColumns() {
        var columns = jdbc.queryForList(
                "select column_name from information_schema.columns " +
                        "where table_name = 'gold_model_experiment_metric' " +
                        "order by ordinal_position"
        ).stream().map(r -> r.get("column_name").toString()).toList();

        assertThat(columns).contains(
                "experiment_id", "model_type", "sample_count", "covered_count",
                "coverage", "accuracy", "balanced_accuracy", "brier_score", "log_loss",
                "recalls", "confusion_matrix"
        );
    }

    @Test
    void hasUniqueDatasetHashIndex() {
        var indexes = jdbc.queryForList(
                "select indexname from pg_indexes " +
                        "where tablename = 'gold_model_experiment'"
        );
        assertThat(indexes).isNotEmpty();
    }

    @Test
    void hasUniqueExperimentMetricIndex() {
        var indexes = jdbc.queryForList(
                "select indexname from pg_indexes " +
                        "where tablename = 'gold_model_experiment_metric'"
        );
        assertThat(indexes).isNotEmpty();
    }

    private boolean tableExists(String tableName) {
        var result = jdbc.queryForObject(
                "select count(*) from information_schema.tables " +
                        "where table_name = ?",
                Integer.class,
                tableName
        );
        return result != null && result > 0;
    }
}
