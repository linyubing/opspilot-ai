package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldDatasetFingerprintTests {

    private final GoldDatasetFingerprint fingerprint = new GoldDatasetFingerprint();

    @Test
    @DisplayName("相同数据集产生相同哈希")
    void sameDatasetProducesSameHash() {
        GoldDataset dataset = sampleDataset();
        String hash1 = fingerprint.hash(dataset);
        String hash2 = fingerprint.hash(dataset);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("样本顺序不影响哈希")
    void sampleOrderDoesNotChangeHash() {
        GoldDataset original = sampleDataset();
        GoldDataset shuffled = shuffledDataset();
        assertThat(fingerprint.hash(original)).isEqualTo(fingerprint.hash(shuffled));
    }

    @Test
    @DisplayName("特征映射顺序不影响哈希")
    void featureMapOrderDoesNotChangeHash() {
        GoldDataset dataset1 = sampleDataset();
        GoldDataset dataset2 = datasetWithReorderedFeatures();
        assertThat(fingerprint.hash(dataset1)).isEqualTo(fingerprint.hash(dataset2));
    }

    @Test
    @DisplayName("特征变化改变哈希")
    void changedFeatureChangesHash() {
        GoldDataset original = sampleDataset();
        GoldDataset modified = datasetWithChangedFeature();
        assertThat(fingerprint.hash(original)).isNotEqualTo(fingerprint.hash(modified));
    }

    @Test
    @DisplayName("标签变化改变哈希")
    void changedLabelChangesHash() {
        GoldDataset original = sampleDataset();
        GoldDataset modified = datasetWithChangedLabel();
        assertThat(fingerprint.hash(original)).isNotEqualTo(fingerprint.hash(modified));
    }

    @Test
    @DisplayName("目标日期变化改变哈希")
    void changedTargetDateChangesHash() {
        GoldDataset original = sampleDataset();
        GoldDataset modified = datasetWithChangedTargetDate();
        assertThat(fingerprint.hash(original)).isNotEqualTo(fingerprint.hash(modified));
    }

    private GoldDataset sampleDataset() {
        return new GoldDataset(samples(), 0);
    }

    private GoldDataset shuffledDataset() {
        var list = new java.util.ArrayList<>(samples());
        java.util.Collections.shuffle(list);
        return new GoldDataset(list, 0);
    }

    private GoldDataset datasetWithReorderedFeatures() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.stream()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(name -> values.put(name, 0.5));
        GoldSample sample = new GoldSample(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                ForecastHorizon.NEXT_DAY,
                new GoldFeatures(values),
                ForecastDirection.BULLISH
        );
        return new GoldDataset(java.util.List.of(sample), 0);
    }

    private GoldDataset datasetWithChangedFeature() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.5));
        values.put("gold_return_1", 0.99);
        GoldSample sample = new GoldSample(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                ForecastHorizon.NEXT_DAY,
                new GoldFeatures(values),
                ForecastDirection.BULLISH
        );
        return new GoldDataset(java.util.List.of(sample), 0);
    }

    private GoldDataset datasetWithChangedLabel() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.5));
        GoldSample sample = new GoldSample(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                ForecastHorizon.NEXT_DAY,
                new GoldFeatures(values),
                ForecastDirection.BEARISH
        );
        return new GoldDataset(java.util.List.of(sample), 0);
    }

    private GoldDataset datasetWithChangedTargetDate() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.5));
        GoldSample sample = new GoldSample(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 3),
                ForecastHorizon.NEXT_DAY,
                new GoldFeatures(values),
                ForecastDirection.BULLISH
        );
        return new GoldDataset(java.util.List.of(sample), 0);
    }

    private java.util.List<GoldSample> samples() {
        Map<String, Double> values = new HashMap<>();
        GoldFeatures.NAMES.forEach(name -> values.put(name, 0.5));
        return java.util.List.of(
                new GoldSample(
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 2),
                        ForecastHorizon.NEXT_DAY,
                        new GoldFeatures(values),
                        ForecastDirection.BULLISH
                )
        );
    }
}
