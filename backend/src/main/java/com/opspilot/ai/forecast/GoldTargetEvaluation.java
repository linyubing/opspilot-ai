package com.opspilot.ai.forecast;

import com.opspilot.ai.marketdata.GoldSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** 独立目标的候选评测：先锁最早发布，再看结算；不修改旧逐条统计或升级可信率。 */
public record GoldTargetEvaluation(String policy, boolean trusted, int excludedCount, List<Group> groups) {
    public GoldTargetEvaluation { groups = List.copyOf(groups); }

    public static GoldTargetEvaluation from(List<StoredGoldDirectionForecast> records) {
        var eligible = records.stream().filter(GoldTargetEvaluation::eligible).toList();
        Map<Key, List<StoredGoldDirectionForecast>> protocols = eligible.stream()
                .collect(Collectors.groupingBy(row -> new Key(row.modelName(), row.promptVersion(),
                        row.forecastRuleVersion(), row.timing().ruleVersion(), row.timing().phase(row.createdAt()), row.reportedModel())));
        Comparator<Key> order = Comparator.comparing(Key::model).thenComparing(Key::prompt)
                .thenComparing(Key::rule).thenComparing(Key::timing).thenComparing(Key::phase)
                .thenComparing(Key::reported, Comparator.nullsFirst(String::compareTo));
        var groups = protocols.entrySet().stream().sorted(Map.Entry.comparingByKey(order))
                .map(entry -> group(entry.getKey(), entry.getValue())).toList();
        return new GoldTargetEvaluation("first-publication-per-target-layer-v2-response-model", false,
                records.size() - eligible.size(), groups);
    }

    private static Group group(Key key, List<StoredGoldDirectionForecast> records) {
        Map<LocalDate, StoredGoldDirectionForecast> first = new LinkedHashMap<>();
        // 必须从全部状态挑首条，不能因首条未结算/作废而改选后来命中的记录。
        records.stream().sorted(Comparator.comparing(StoredGoldDirectionForecast::createdAt)
                .thenComparing(StoredGoldDirectionForecast::id))
                .forEach(row -> first.putIfAbsent(row.timing().targetDate(), row));
        var resolved = first.values().stream().filter(row -> row.status() == ForecastStatus.RESOLVED
                && row.actualDirection() != null
                && row.timing().canResolve(row.baseDate(), row.targetDate(), row.createdAt(), row.resolvedAt())).toList();
        var scored = resolved.stream().filter(GoldSignalEvaluation::isScoredSignal).toList();
        long hits = scored.stream().filter(row -> Boolean.TRUE.equals(row.hit())).count();
        long neutral = scored.stream().filter(row -> row.actualDirection() == ForecastDirection.NEUTRAL).count();
        var signals = GoldSignalEvaluation.from(List.copyOf(first.values()));
        return new Group(key.model(), key.prompt(), key.rule(), key.timing(), key.phase(), first.size(),
                resolved.size(), first.size() - resolved.size(), records.size() - first.size(),
                divide(hits, scored.size()), divide(neutral, scored.size()), signals.signalCount(),
                signals.declaredSignalCount(), signals.abstainedCount(), signals.legacyCount(), signals.coverage(),
                resolved.size() == first.size() ? signals.fullSampleAccuracy() : null, signals.note(), key.reported());
    }

    private static boolean eligible(StoredGoldDirectionForecast row) {
        if (row.timing() == null || row.createdAt() == null || row.baseDate() == null
                || row.modelName() == null || row.promptVersion() == null || row.forecastRuleVersion() == null
                || !row.timing().targetDate().isAfter(row.baseDate())) return false;
        var time = row.createdAt().toInstant();
        return !time.isBefore(GoldSession.forDate(row.baseDate()).end()) && time.isBefore(row.timing().end());
    }

    private static BigDecimal divide(long count, int total) {
        return total == 0 ? null : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private record Key(String model, String prompt, String rule, String timing, GoldForecastTiming.Phase phase, String reported) { }

    /** 每个模型协议及发布层独立展示；未结算首条不进入准确率分母。 */
    public record Group(String modelName, String promptVersion, String forecastRuleVersion, String timingRule,
            GoldForecastTiming.Phase phase, int selectedCount, int resolvedCount, int unresolvedCount,
            int duplicatesIgnored, BigDecimal accuracy, BigDecimal neutralBaselineAccuracy,
            int signalCount, int declaredSignalCount, int abstainedCount, int legacyCount,
            BigDecimal coverage, BigDecimal fullSampleAccuracy, String note, String reportedModel) {
        /** 兼容旧独立目标汇总，不能从数量反推新协议覆盖率。 */
        public Group(String modelName, String promptVersion, String forecastRuleVersion, String timingRule,
                GoldForecastTiming.Phase phase, int selectedCount, int resolvedCount, int unresolvedCount,
                int duplicatesIgnored, BigDecimal accuracy, BigDecimal neutralBaselineAccuracy) {
            this(modelName, promptVersion, forecastRuleVersion, timingRule, phase, selectedCount, resolvedCount,
                    unresolvedCount, duplicatesIgnored, accuracy, neutralBaselineAccuracy, resolvedCount,
                    0, 0, selectedCount, null, null, "旧汇总没有逐条信号协议证据，覆盖率与完整样本准确率不可得。", null);
        }
    }
}
