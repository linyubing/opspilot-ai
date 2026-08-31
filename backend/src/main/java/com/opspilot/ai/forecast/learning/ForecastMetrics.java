package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** 保存统一的黄金分类评估指标。 */
public record ForecastMetrics(
        int sampleCount,
        int coveredCount,
        BigDecimal coverage,
        BigDecimal accuracy,
        BigDecimal balancedAccuracy,
        BigDecimal brierScore,
        Map<ForecastDirection, BigDecimal> recalls,
        Map<ForecastDirection, Map<ForecastDirection, Integer>> confusionMatrix,
        boolean promotionReady
) {
    public ForecastMetrics {
        // EnumMap 允许用 null 明确表示“该类别没有样本，召回率不可计算”。
        recalls = Collections.unmodifiableMap(new EnumMap<>(recalls));
        confusionMatrix = Map.copyOf(confusionMatrix);
    }
}
