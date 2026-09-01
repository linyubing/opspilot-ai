package com.opspilot.ai.forecast.learning;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 模型实验数据访问接口。 */
public interface ModelExperimentRepository {

    void create(ModelExperiment experiment);

    void markRunning(UUID id, OffsetDateTime startedAt);

    void complete(
            UUID id,
            ModelExperiment completed,
            List<ModelExperimentMetric> metrics
    );

    void fail(UUID id, String message, OffsetDateTime completedAt);

    Optional<ModelExperiment> findById(UUID id);

    List<ModelExperiment> findRecent(int limit);

    List<ModelExperimentMetric> findMetrics(UUID experimentId);
}
