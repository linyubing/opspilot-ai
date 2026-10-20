package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 保存完整日期评分及候选信号日期上的基线；零信号时基线明确缺失。 */
public record PairedMetrics(
        ForecastMetrics all,
        ForecastMetrics baselineOnSignals,
        List<LocalDate> signalDates
) {
    public PairedMetrics {
        Objects.requireNonNull(all, "完整日期指标不能为空");
        signalDates = List.copyOf(signalDates);
        if (all.sampleCount() != all.coveredCount()
                || new HashSet<>(signalDates).size() != signalDates.size()
                || signalDates.size() > all.sampleCount()) {
            throw new IllegalArgumentException("配对日期或完整样本数量不一致");
        }
        if (signalDates.isEmpty() ? baselineOnSignals != null
                : baselineOnSignals == null
                    || baselineOnSignals.sampleCount() != signalDates.size()
                    || baselineOnSignals.coveredCount() != signalDates.size()) {
            throw new IllegalArgumentException("同信号日期基线数量不一致");
        }
    }
}
