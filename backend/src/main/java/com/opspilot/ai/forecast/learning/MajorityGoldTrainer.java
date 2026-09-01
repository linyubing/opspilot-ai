package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 训练始终输出训练集多数方向的基础分类器。 */
@Component("majorityGoldTrainer")
public class MajorityGoldTrainer implements GoldTrainer {

    @Override
    public String name() {
        return "majority-v1";
    }

    @Override
    public GoldClassifier train(List<GoldSample> samples, Set<String> featureNames) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("训练样本不能为空");
        }
        Map<ForecastDirection, Long> counts = new EnumMap<>(ForecastDirection.class);
        for (ForecastDirection direction : ForecastDirection.values()) {
            counts.put(direction, 0L);
        }
        samples.forEach(sample -> counts.compute(sample.label(), (key, value) -> value + 1));

        ForecastDirection majority = List.of(
                        ForecastDirection.NEUTRAL,
                        ForecastDirection.BULLISH,
                        ForecastDirection.BEARISH
                ).stream()
                .max((left, right) -> Long.compare(counts.get(left), counts.get(right)))
                .orElseThrow();
        DirectionProbabilities probabilities = probabilities(majority);
        return features -> probabilities;
    }

    private DirectionProbabilities probabilities(ForecastDirection direction) {
        return switch (direction) {
            case BULLISH -> new DirectionProbabilities(1, 0, 0);
            case NEUTRAL -> new DirectionProbabilities(0, 1, 0);
            case BEARISH -> new DirectionProbabilities(0, 0, 1);
        };
    }
}
