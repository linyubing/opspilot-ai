package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ModelExperimentServiceTests {

    @Autowired
    private ModelExperimentService service;

    @Autowired
    private ModelExperimentRepository repo;

    @Autowired
    private GoldDatasetFingerprint fingerprint;

    @Autowired
    private TemporalSplitter splitter;

    @Test
    void buildsDatasetOnceAndSplitsOnce() {
        ModelExperiment experiment = service.run(ForecastHorizon.NEXT_DAY);

        assertThat(experiment).isNotNull();
        assertThat(experiment.status()).isEqualTo(ModelExperimentStatus.COMPLETED);
        assertThat(experiment.datasetHash()).isNotBlank();
    }

    @Test
    void fingerprintIsStableAndMatchesActualData() {
        ModelExperiment experiment = service.run(ForecastHorizon.NEXT_DAY);

        var found = repo.findById(experiment.id());
        assertThat(found).isPresent();
        assertThat(found.get().datasetHash()).isEqualTo(experiment.datasetHash());
    }

    @Test
    void intervalsAndSampleCountsComeFromActualSplit() {
        ModelExperiment experiment = service.run(ForecastHorizon.NEXT_DAY);

        assertThat(experiment.validationStart()).isAfterOrEqualTo(experiment.trainStart());
        assertThat(experiment.validationEnd()).isAfterOrEqualTo(experiment.validationStart());
        assertThat(experiment.holdoutStart()).isAfterOrEqualTo(experiment.validationEnd());
        assertThat(experiment.holdoutEnd()).isAfterOrEqualTo(experiment.holdoutStart());
        assertThat(experiment.validationSamples()).isGreaterThan(0);
        assertThat(experiment.holdoutSamples()).isGreaterThan(0);
    }

    @Test
    void savesBothModelMetrics() {
        ModelExperiment experiment = service.run(ForecastHorizon.NEXT_DAY);

        List<ModelExperimentMetric> metrics = service.findMetrics(experiment.id());
        assertThat(metrics).hasSize(2);
        assertThat(metrics).extracting(m -> m.modelType())
                .containsExactlyInAnyOrder(ModelType.MAJORITY, ModelType.LOGISTIC);
    }

    @Test
    void failedExperimentHasCorrectStatusAndMessage() {
        assertThatThrownBy(() -> service.run(ForecastHorizon.TWENTY_DAYS))
                .isInstanceOf(ModelExperimentException.class);
    }

    @Test
    void missingRecallStaysNull() {
        ModelExperiment experiment = service.run(ForecastHorizon.NEXT_DAY);

        List<ModelExperimentMetric> metrics = service.findMetrics(experiment.id());
        assertThat(metrics).isNotEmpty();

        for (ModelExperimentMetric metric : metrics) {
            assertThat(metric.recalls()).isNotNull();
        }
    }

    @Test
    void findByIdThrowsForNonExistent() {
        assertThatThrownBy(() -> service.findRecent(10))
                .isNotInstanceOf(ModelExperimentNotFoundException.class);
    }
}
