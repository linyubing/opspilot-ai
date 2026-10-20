package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 区分显式信号协议、弃权和旧未知协议；基线只评分相同的已结算信号记录。 */
public record GoldSignalEvaluation(int sampleCount, int signalCount, int declaredSignalCount,
        int abstainedCount, int legacyCount, int scoredCount, BigDecimal coverage, BigDecimal signalAccuracy,
        BigDecimal neutralBaselineAccuracy, BigDecimal fullSampleAccuracy, String note) {

    public static GoldSignalEvaluation from(List<StoredGoldDirectionForecast> records) {
        int declared = (int) records.stream().filter(r -> r.signal() == ForecastSignal.PREDICTED && r.hasSignal()).count();
        int abstained = (int) records.stream().filter(r -> r.signal() == ForecastSignal.ABSTAINED).count();
        int legacy = (int) records.stream().filter(r -> r.signal() == null).count();
        int signals = (int) records.stream().filter(StoredGoldDirectionForecast::hasSignal).count();
        var scored = records.stream().filter(GoldSignalEvaluation::isScoredSignal).toList();
        long hits = scored.stream().filter(r -> Boolean.TRUE.equals(r.hit())).count();
        long neutral = scored.stream().filter(r -> r.actualDirection() == ForecastDirection.NEUTRAL).count();
        boolean complete = !records.isEmpty() && declared == records.size() && scored.size() == records.size();
        return new GoldSignalEvaluation(records.size(), signals, declared, abstained, legacy, scored.size(),
                ratio(declared, declared + abstained), ratio(hits, scored.size()), ratio(neutral, scored.size()),
                complete ? ratio(hits, records.size()) : null,
                "覆盖率仅以显式信号协议为分母，旧协议独立计数；信号准确率和中性基线仅用同一已结算信号集合。"
                        + "存在弃权、旧未知协议或未结算时，缺少全样本强制方向/概率，完整样本准确率不可得，不能补为中性。");
    }

    /** 没有方向或命中证据的历史记录只能保留计数，不作为方向失败。 */
    static boolean isScoredSignal(StoredGoldDirectionForecast record) {
        return record.status() == ForecastStatus.RESOLVED && record.hasSignal()
                && record.actualDirection() != null && record.hit() != null;
    }

    private static BigDecimal ratio(long numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
