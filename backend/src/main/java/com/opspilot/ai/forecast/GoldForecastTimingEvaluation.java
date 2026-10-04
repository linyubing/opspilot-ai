package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 将旧未知、候选开盘前和盘中样本分开，不把候选证据升级为可信率。 */
public record GoldForecastTimingEvaluation(Group unknown, Group beforeSession,
        Group inSession, Group invalid, int trustedCount, BigDecimal trustedAccuracy) {

    public static GoldForecastTimingEvaluation from(List<StoredGoldDirectionForecast> resolved) {
        var unknown = new ArrayList<StoredGoldDirectionForecast>();
        var before = new ArrayList<StoredGoldDirectionForecast>();
        var during = new ArrayList<StoredGoldDirectionForecast>();
        var invalid = new ArrayList<StoredGoldDirectionForecast>();
        for (var record : resolved) {
            var timing = record.timing();
            if (timing == null) unknown.add(record);
            else if (!timing.canResolve(record.baseDate(), record.targetDate(), record.createdAt(), record.resolvedAt()))
                invalid.add(record);
            else if (timing.phase(record.createdAt()) == GoldForecastTiming.Phase.BEFORE_SESSION) before.add(record);
            else during.add(record);
        }
        // 当前只有候选与未知依据，不能因生成时间看似有效就宣称完整可信。
        return new GoldForecastTimingEvaluation(group(unknown), group(before), group(during), group(invalid), 0, null);
    }

    /** 兼容没有逐条发布时间资料的旧评测调用，只能将其标为未知。 */
    public static GoldForecastTimingEvaluation legacy(int count, BigDecimal accuracy) {
        return new GoldForecastTimingEvaluation(new Group(count, accuracy), new Group(0, null),
                new Group(0, null), new Group(0, null), 0, null);
    }

    private static Group group(List<StoredGoldDirectionForecast> records) {
        if (records.isEmpty()) return new Group(0, null);
        long hits = records.stream().filter(row -> Boolean.TRUE.equals(row.hit())).count();
        return new Group(records.size(), BigDecimal.valueOf(hits)
                .divide(BigDecimal.valueOf(records.size()), 4, RoundingMode.HALF_UP));
    }

    /** 同一时间资格内的历史描述，仍不代表未来预测能力。 */
    public record Group(int sampleCount, BigDecimal accuracy) {}
}
