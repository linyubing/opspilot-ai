package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 保存标准化前后的配对开发验证结果，不能用作自动晋级依据。 */
public record ScalingReport(
        UUID id, OffsetDateTime createdAt, String gitCommit, String datasetHash,
        ForecastHorizon horizon, LocalDate validationStart, LocalDate validationEnd,
        LocalDate holdoutStart, LocalDate holdoutEnd, int sampleCount, int skippedCount,
        double confidence, List<Result> results
) {
    public ScalingReport { results = List.copyOf(results); }

    /** 每个特征组合共享同一训练窗口和评分日期。 */
    public record Result(FeatureProfile profile, Score raw, Score scaled, Score majority,
                         ForecastMetrics rawDateBaseline, ForecastMetrics scaledDateBaseline,
                         Pair paired, List<Block> blocks) {
        public Result { blocks = List.copyOf(blocks); }
    }

    /** all 评估全部日期；signals 保留覆盖率口径；selectedDates 仅评估信号日期，可与同日期基线比较。 */
    public record Score(String version, ForecastMetrics all, ForecastMetrics signals,
                        ForecastMetrics selectedDates) { }

    /** 共同信号日期的准确次数，以及全样本逐条胜负配对计数。 */
    public record Pair(int commonSignals, int rawSignalHits, int scaledSignalHits,
                       int scaledOnlyHits, int rawOnlyHits) { }

    /** 连续 20 条验证样本的命中计数，用于观察改善是否集中在少数时段。 */
    public record Block(LocalDate start, LocalDate end, int samples,
                        int rawHits, int scaledHits, int majorityHits) { }
}
