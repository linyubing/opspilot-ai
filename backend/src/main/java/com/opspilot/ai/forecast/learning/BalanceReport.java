package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.forecast.ForecastDirection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 保存类别加权的下一交易日配对实验；两侧均标准化，不代表正式晋级。 */
public record BalanceReport(UUID id, OffsetDateTime createdAt, String gitCommit, String datasetHash,
        ForecastHorizon horizon, LocalDate validationStart, LocalDate validationEnd,
        LocalDate holdoutStart, LocalDate holdoutEnd, int sampleCount, int skippedCount,
        double confidence, Map<ForecastDirection, Integer> initialCounts,
        Map<ForecastDirection, Integer> validationCounts, List<Result> results) {
    public BalanceReport {
        initialCounts = Map.copyOf(initialCounts);
        validationCounts = Map.copyOf(validationCounts);
        results = List.copyOf(results);
    }

    /**
     * baseline 是不加权的标准化模型，balanced 是加权模型。
     * 两个 DateBaseline 字段均为各自出信号日期上的多数类基线，避免比较不同日期。
     */
    public record Result(FeatureProfile profile, ScalingReport.Score baseline, ScalingReport.Score balanced,
            ScalingReport.Score majority, ForecastMetrics baselineDateBaseline, ForecastMetrics balancedDateBaseline,
            int commonSignals, int baselineSignalHits, int balancedSignalHits,
            int balancedOnlyHits, int baselineOnlyHits, List<Block> blocks) {
        public Result { blocks = List.copyOf(blocks); }

        static Result from(ScalingReport.Result pair) {
            return new Result(pair.profile(), pair.raw(), pair.scaled(), pair.majority(),
                    pair.rawDateBaseline(), pair.scaledDateBaseline(), pair.paired().commonSignals(),
                    pair.paired().rawSignalHits(), pair.paired().scaledSignalHits(),
                    pair.paired().scaledOnlyHits(), pair.paired().rawOnlyHits(),
                    pair.blocks().stream().map(b -> new Block(b.start(), b.end(), b.samples(),
                            b.rawHits(), b.scaledHits(), b.majorityHits())).toList());
        }
    }

    /** 按连续 20 条开发样本检查改善是否集中在少数时段。 */
    public record Block(LocalDate start, LocalDate end, int samples,
                        int baselineHits, int balancedHits, int majorityHits) { }
}
