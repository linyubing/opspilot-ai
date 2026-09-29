package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;

/** 从单次训练集计算类别权重，不生成或复制任何行情样本。 */
final class ClassWeights {
    private ClassWeights() { }

    static Map<ForecastDirection, Float> fit(List<GoldSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("类别加权需要非空训练样本");
        }
        Map<ForecastDirection, Integer> counts = new EnumMap<>(ForecastDirection.class);
        for (GoldSample sample : samples) counts.merge(sample.label(), 1, Integer::sum);
        Map<ForecastDirection, Float> weights = new EnumMap<>(ForecastDirection.class);
        for (ForecastDirection direction : ForecastDirection.values()) {
            int count = counts.getOrDefault(direction, 0);
            if (count == 0) throw new IllegalArgumentException("训练样本缺少方向，不能加权补造：" + direction);
            // N/(3*类别数量)：让三类在训练损失中的总权重相等，平均样本权重仍为 1。
            weights.put(direction, (float) (samples.size() / (3.0 * count)));
        }
        return Map.copyOf(weights);
    }
}
