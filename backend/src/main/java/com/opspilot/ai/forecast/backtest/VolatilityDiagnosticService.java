package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.ForecastDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 使用预测日之前的波动率阈值，统计各波动区间的方向命中率。 */
public class VolatilityDiagnosticService {

    private static final int MIN_HISTORY = 20;
    private final VolatilityRegimeClassifier classifier =
            new VolatilityRegimeClassifier();

    public List<VolatilityDiagnostic> diagnose(
            List<VolatilitySample> samples
    ) {
        List<VolatilitySample> sorted = samples.stream()
                .sorted(Comparator.comparing(VolatilitySample::date))
                .toList();
        Map<VolatilityRegime, Counts> counts = new EnumMap<>(
                VolatilityRegime.class
        );
        for (VolatilityRegime regime : VolatilityRegime.values()) {
            counts.put(regime, new Counts());
        }

        List<BigDecimal> history = new ArrayList<>();
        for (VolatilitySample sample : sorted) {
            if (history.size() >= MIN_HISTORY) {
                VolatilityRegime regime = classifier.classify(
                        sample.volatility(), history
                );
                Counts value = counts.get(regime);
                value.samples++;
                if (sample.predicted() != ForecastDirection.NEUTRAL) {
                    value.signals++;
                    if (sample.predicted() == sample.actual()) {
                        value.hits++;
                    }
                }
            }
            history.add(sample.volatility());
        }

        return List.of(VolatilityRegime.values()).stream()
                .map(regime -> result(regime, counts.get(regime)))
                .toList();
    }

    private VolatilityDiagnostic result(
            VolatilityRegime regime,
            Counts counts
    ) {
        BigDecimal accuracy = counts.signals == 0 ? null
                : BigDecimal.valueOf(counts.hits).divide(
                        BigDecimal.valueOf(counts.signals),
                        4,
                        RoundingMode.HALF_UP
                );
        return new VolatilityDiagnostic(
                regime, counts.samples, counts.signals,
                counts.hits, accuracy
        );
    }

    private static class Counts {
        private int samples;
        private int signals;
        private int hits;
    }
}
