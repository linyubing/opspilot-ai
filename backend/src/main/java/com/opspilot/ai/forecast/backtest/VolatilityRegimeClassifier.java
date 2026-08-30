package com.opspilot.ai.forecast.backtest;

import java.math.BigDecimal;
import java.util.List;

/** 只使用当前日期之前的波动率样本确定当前波动区间。 */
public class VolatilityRegimeClassifier {

    private static final int MIN_HISTORY = 20;

    public VolatilityRegime classify(
            BigDecimal current,
            List<BigDecimal> history
    ) {
        if (current == null || history == null
                || history.size() < MIN_HISTORY) {
            throw new IllegalArgumentException(
                    "波动区间判断至少需要20条历史波动率"
            );
        }

        List<BigDecimal> sorted = history.stream().sorted().toList();
        BigDecimal low = sorted.get(sorted.size() / 3);
        BigDecimal high = sorted.get(sorted.size() * 2 / 3);

        if (current.compareTo(low) <= 0) {
            return VolatilityRegime.LOW;
        }
        if (current.compareTo(high) <= 0) {
            return VolatilityRegime.MEDIUM;
        }
        return VolatilityRegime.HIGH;
    }
}
