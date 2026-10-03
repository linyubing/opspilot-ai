package com.opspilot.ai.analysis;

import com.opspilot.ai.marketdata.GoldDailyBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 留存快照实际使用的黄金窗口；闭市确认不等于历史当时可得性。 */
public record GoldSnapshotInput(List<GoldDailyBar> bars, OffsetDateTime checkedAt) {
    public GoldSnapshotInput {
        bars = bars == null ? List.of() : List.copyOf(bars);
    }

    /** 核对窗口依据、时间及黄金指标是否匹配。 */
    public boolean matches(LocalDate date, GoldReturnMetrics gold, OffsetDateTime asOf) {
        if (date == null || gold == null || checkedAt == null || asOf == null
                || checkedAt.isAfter(asOf) || bars.size() != 21) return false;
        for (int i = 0; i < bars.size(); i++) {
            GoldDailyBar bar = bars.get(i);
            if (!validBar(bar) || !bar.isConfirmedAt(checkedAt)) return false;
            if (i > 0 && !bar.priceDate().isBefore(bars.get(i - 1).priceDate())) return false;
        }
        GoldDailyBar base = bars.getFirst();
        if (!date.equals(base.priceDate()) || gold.collectedAt() == null
                || !gold.collectedAt().isEqual(base.collectedAt())) return false;
        try {
            return same(base.close(), gold.currentPrice())
                    && same(change(1), gold.return1())
                    && same(change(5), gold.return5())
                    && same(change(20), gold.return20())
                    && same(new GoldVolatilityCalculator().calculateBars(bars), gold.volatility20());
        } catch (ArithmeticException | NumberFormatException invalid) {
            // 极端非法数值不应被当成可用留痕。
            return false;
        }
    }

    private boolean validBar(GoldDailyBar bar) {
        return "XAUUSD".equals(bar.symbol()) && "twelve_data".equals(bar.provider())
                && "usd".equals(bar.currency()) && "troy_ounce".equals(bar.unit())
                && positive(bar.open()) && positive(bar.high())
                && positive(bar.low()) && positive(bar.close())
                && bar.high().compareTo(bar.open().max(bar.close())) >= 0
                && bar.low().compareTo(bar.open().min(bar.close())) <= 0;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0
                && Double.isFinite(value.doubleValue()) && value.doubleValue() > 0;
    }

    private BigDecimal change(int index) {
        return bars.getFirst().close().divide(bars.get(index).close(), MathContext.DECIMAL128)
                .subtract(BigDecimal.ONE).multiply(new BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private boolean same(BigDecimal expected, BigDecimal actual) {
        return actual != null && expected.compareTo(actual) == 0;
    }
}
