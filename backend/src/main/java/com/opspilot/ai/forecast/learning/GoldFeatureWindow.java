package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.marketdata.GoldDailyBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 黄金 OHLC 特征计算所需的滑动窗口数据。
 *
 * <p>仅使用 asOfDate 当日及以前的真实 OHLC 数据，不允许未来数据泄漏。
 * 窗口大小默认为 20 个交易日。
 */
class GoldFeatureWindow {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext MC = MathContext.DECIMAL128;

    private final LocalDate asOfDate;
    private final List<GoldDailyBar> bars; // 按日期升序，仅包含 ≤ asOfDate 的数据

    GoldFeatureWindow(LocalDate asOfDate, List<GoldDailyBar> allBars) {
        this.asOfDate = asOfDate;
        this.bars = allBars.stream()
                .filter(b -> !b.priceDate().isAfter(asOfDate))
                .sorted((a, b) -> a.priceDate().compareTo(b.priceDate()))
                .toList();
    }

    LocalDate asOfDate() {
        return asOfDate;
    }

    int size() {
        return bars.size();
    }

    /** 返回最近 n 根 K 线（含当日）。若数据不足则返回全部可用数据。 */
    List<GoldDailyBar> recent(int n) {
        int start = Math.max(0, bars.size() - n);
        return List.copyOf(bars.subList(start, bars.size()));
    }

    /** 返回指定偏移量的 K 线（0=当日，-1=前一日）。若越界返回 null。 */
    GoldDailyBar at(int offset) {
        int idx = bars.size() - 1 + offset;
        if (idx < 0 || idx >= bars.size()) {
            return null;
        }
        return bars.get(idx);
    }

    BigDecimal close() {
        return last().close();
    }

    BigDecimal high() {
        return last().high();
    }

    BigDecimal low() {
        return last().low();
    }

    BigDecimal open() {
        return last().open();
    }

    GoldDailyBar last() {
        return bars.get(bars.size() - 1);
    }

    /** 计算 n 日收益率（%）。返回 null 表示数据不足。 */
    BigDecimal returnN(int n) {
        GoldDailyBar current = last();
        GoldDailyBar past = at(-(n));
        if (past == null || past.close().signum() == 0) {
            return null;
        }
        return current.close()
                .divide(past.close(), MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算隔夜缺口（%）。 */
    BigDecimal overnightGap() {
        GoldDailyBar current = last();
        GoldDailyBar prev = at(-1);
        if (prev == null || prev.close().signum() == 0) {
            return null;
        }
        return current.open()
                .divide(prev.close(), MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算日内收益率（%）。 */
    BigDecimal intradayReturn() {
        GoldDailyBar current = last();
        if (current.open().signum() == 0) {
            return null;
        }
        return current.close()
                .divide(current.open(), MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算日内波幅（%）。 */
    BigDecimal dailyRange() {
        GoldDailyBar current = last();
        if (current.open().signum() == 0) {
            return null;
        }
        return current.high()
                .subtract(current.low())
                .divide(current.open(), MC)
                .multiply(HUNDRED);
    }

    /** 计算收盘位置。high == low 时返回 0.5。 */
    BigDecimal closeLocation() {
        GoldDailyBar current = last();
        BigDecimal range = current.high().subtract(current.low());
        if (range.signum() == 0) {
            return new BigDecimal("0.5");
        }
        return current.close()
                .subtract(current.low())
                .divide(range, MC);
    }

    /** 计算 ATR(14)。若数据不足 15 根 K 线返回 null。 */
    BigDecimal atr14() {
        if (bars.size() < 15) {
            return null;
        }
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = bars.size() - 14; i < bars.size(); i++) {
            GoldDailyBar b = bars.get(i);
            GoldDailyBar prev = (i > 0) ? bars.get(i - 1) : null;
            BigDecimal high = b.high();
            BigDecimal low = b.low();
            BigDecimal prevClose = (prev != null) ? prev.close() : b.open();
            BigDecimal tr = high.max(prevClose).subtract(low.min(prevClose));
            trs.add(tr);
        }
        BigDecimal sum = trs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(trs.size()), MC);
    }

    /** 计算 n 日波动率（% 年化）。返回 null 表示数据不足。 */
    BigDecimal volatility(int n) {
        List<GoldDailyBar> window = recent(n);
        if (window.size() < n) {
            return null;
        }
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < window.size(); i++) {
            BigDecimal prevClose = window.get(i - 1).close();
            BigDecimal currClose = window.get(i).close();
            if (prevClose.signum() == 0) {
                continue;
            }
            returns.add(currClose.divide(prevClose, MC)
                    .subtract(BigDecimal.ONE));
        }
        if (returns.isEmpty()) {
            return null;
        }
        BigDecimal mean = returns.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(returns.size()), MC);
        BigDecimal variance = returns.stream()
                .map(r -> r.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(returns.size()), MC);
        // 年化波动率 = std * sqrt(252) * 100
        double std = Math.sqrt(variance.doubleValue());
        double annualized = std * Math.sqrt(252) * 100;
        return BigDecimal.valueOf(annualized);
    }

    /** 计算 MA(n) 距离（%）。返回 null 表示数据不足。 */
    BigDecimal maDistance(int n) {
        List<GoldDailyBar> window = recent(n);
        if (window.size() < n) {
            return null;
        }
        BigDecimal sum = window.stream()
                .map(GoldDailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ma = sum.divide(new BigDecimal(n), MC);
        if (ma.signum() == 0) {
            return null;
        }
        return close()
                .divide(ma, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算 MA(n) 斜率（%）。返回 null 表示数据不足。 */
    BigDecimal maSlope(int n) {
        List<GoldDailyBar> window = recent(n + 1);
        if (window.size() < n + 1) {
            return null;
        }
        // 当前 MA
        List<GoldDailyBar> currentWindow = window.subList(
                window.size() - n, window.size());
        BigDecimal currentMa = currentWindow.stream()
                .map(GoldDailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(n), MC);
        // 前一日 MA
        List<GoldDailyBar> prevWindow = window.subList(
                window.size() - n - 1, window.size() - 1);
        BigDecimal prevMa = prevWindow.stream()
                .map(GoldDailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(n), MC);
        if (prevMa.signum() == 0) {
            return null;
        }
        return currentMa
                .divide(prevMa, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算 RSI(14)。若数据不足 15 根 K 线返回 null。 */
    BigDecimal rsi14() {
        if (bars.size() < 15) {
            return null;
        }
        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (int i = bars.size() - 14; i < bars.size(); i++) {
            GoldDailyBar b = bars.get(i);
            GoldDailyBar prev = bars.get(i - 1);
            BigDecimal diff = b.close().subtract(prev.close());
            if (diff.compareTo(BigDecimal.ZERO) >= 0) {
                gains.add(diff);
                losses.add(BigDecimal.ZERO);
            } else {
                gains.add(BigDecimal.ZERO);
                losses.add(diff.negate());
            }
        }
        BigDecimal avgGain = gains.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(14), MC);
        BigDecimal avgLoss = losses.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(14), MC);
        if (avgLoss.signum() == 0) {
            return new BigDecimal("100");
        }
        BigDecimal rs = avgGain.divide(avgLoss, MC);
        return BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100)
                        .divide(rs.add(BigDecimal.ONE), MC));
    }

    /** 计算 20 日最大回撤（%）。返回 null 表示数据不足。 */
    BigDecimal drawdown20() {
        List<GoldDailyBar> window = recent(20);
        if (window.size() < 2) {
            return null;
        }
        BigDecimal peak = window.get(0).close();
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (GoldDailyBar bar : window) {
            if (bar.close().compareTo(peak) > 0) {
                peak = bar.close();
            }
            BigDecimal drawdown = peak.subtract(bar.close())
                    .divide(peak, MC)
                    .multiply(HUNDRED);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }

    /** 计算 20 日新高突破（%）。返回 null 表示数据不足。 */
    BigDecimal highBreakout20() {
        List<GoldDailyBar> window = recent(20);
        if (window.size() < 2) {
            return null;
        }
        BigDecimal prevHigh = window.subList(0, window.size() - 1).stream()
                .map(GoldDailyBar::high)
                .max(BigDecimal::compareTo)
                .orElse(null);
        if (prevHigh == null || prevHigh.signum() == 0) {
            return null;
        }
        return high()
                .divide(prevHigh, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算 20 日新低跌破（%）。返回 null 表示数据不足。 */
    BigDecimal lowBreakdown20() {
        List<GoldDailyBar> window = recent(20);
        if (window.size() < 2) {
            return null;
        }
        BigDecimal prevLow = window.subList(0, window.size() - 1).stream()
                .map(GoldDailyBar::low)
                .min(BigDecimal::compareTo)
                .orElse(null);
        if (prevLow == null || prevLow.signum() == 0) {
            return null;
        }
        return low()
                .divide(prevLow, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }
}
