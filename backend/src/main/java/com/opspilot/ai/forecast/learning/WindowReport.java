package com.opspilot.ai.forecast.learning;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 对比完整历史与近期训练窗口，两侧都使用逐折标准化，不自动晋级。 */
public record WindowReport(UUID id, OffsetDateTime createdAt, String gitCommit, String datasetHash,
        ForecastHorizon horizon, LocalDate validationStart, LocalDate validationEnd,
        LocalDate holdoutStart, LocalDate holdoutEnd, int sampleCount, int skippedCount,
        double confidence, List<Result> results) {
    public WindowReport { results = List.copyOf(results); }

    /** trainSize 是训练样本条数，不是自然日数。 */
    public record Result(FeatureProfile profile, int trainSize, ScalingReport.Score full,
            ScalingReport.Score recent, ScalingReport.Score majority,
            ForecastMetrics fullDateBaseline, ForecastMetrics recentDateBaseline,
            int commonSignals, int fullSignalHits, int recentSignalHits,
            int recentOnlyHits, int fullOnlyHits, List<Block> blocks) {
        public Result { blocks = List.copyOf(blocks); }

        static Result from(int size, ScalingReport.Result pair) {
            return new Result(pair.profile(), size, pair.raw(), pair.scaled(), pair.majority(),
                    pair.rawDateBaseline(), pair.scaledDateBaseline(), pair.paired().commonSignals(),
                    pair.paired().rawSignalHits(), pair.paired().scaledSignalHits(),
                    pair.paired().scaledOnlyHits(), pair.paired().rawOnlyHits(),
                    pair.blocks().stream().map(b -> new Block(b.start(), b.end(), b.samples(),
                            b.rawHits(), b.scaledHits(), b.majorityHits())).toList());
        }
    }

    /** 连续验证区块的配对命中数。 */
    public record Block(LocalDate start, LocalDate end, int samples,
                        int fullHits, int recentHits, int majorityHits) { }
}
