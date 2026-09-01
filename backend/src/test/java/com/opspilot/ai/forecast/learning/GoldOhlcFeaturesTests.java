package com.opspilot.ai.forecast.learning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 GoldOhlcFeatures 的完整性校验。 */
class GoldOhlcFeaturesTests {

    @Test
    @DisplayName("拒绝字段缺失")
    void rejectsIncompleteFeatures() {
        Map<String, Double> values = new HashMap<>();
        GoldOhlcFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        values.remove("rsi14");

        assertThatThrownBy(() -> new GoldOhlcFeatures(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("拒绝 NaN 值")
    void rejectsNanValue() {
        Map<String, Double> values = new HashMap<>();
        GoldOhlcFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        values.put("rsi14", Double.NaN);

        assertThatThrownBy(() -> new GoldOhlcFeatures(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限数值");
    }

    @Test
    @DisplayName("拒绝 Infinity 值")
    void rejectsInfinityValue() {
        Map<String, Double> values = new HashMap<>();
        GoldOhlcFeatures.NAMES.forEach(name -> values.put(name, 0.0));
        values.put("atr14", Double.POSITIVE_INFINITY);

        assertThatThrownBy(() -> new GoldOhlcFeatures(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限数值");
    }
}
