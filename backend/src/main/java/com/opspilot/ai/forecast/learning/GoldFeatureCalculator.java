package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.marketdata.GoldDailyBar;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 黄金 OHLC 技术特征计算器。
 *
 * <p>专注于特征计算逻辑，不再查询 Repository。
 * 所有特征仅使用 asOfDate 当日及以前的真实数据，不允许未来数据泄漏。
 * 数据不足时返回 Optional.empty()，DatasetBuilder 遇到 empty 时跳过样本。
 */
@Service
class GoldFeatureCalculator {

    private static final int REQUIRED_BARS = 21;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * 计算指定日期的所有 OHLC 技术特征。
     *
     * @param asOfDate 预测时点日期
     * @param bars     全部日线数据（按日期升序）
     * @return 特征对象，数据不足时返回 Optional.empty()
     */
    Optional<GoldOhlcFeatures> compute(LocalDate asOfDate, List<GoldDailyBar> bars) {
        List<GoldDailyBar> window = bars.stream()
                .filter(b -> !b.priceDate().isAfter(asOfDate))
                .toList();

        if (window.size() < REQUIRED_BARS) {
            return Optional.empty();
        }

        // 取最近 REQUIRED_BARS 根 K 线（含当日）
        List<GoldDailyBar> recent = window.subList(
                window.size() - REQUIRED_BARS, window.size());

        java.util.Map<String, Double> values = new java.util.HashMap<>();
        values.put("return1", bdr(returnN(recent, 1)));
        values.put("return3", bdr(returnN(recent, 3)));
        values.put("return5", bdr(returnN(recent, 5)));
        values.put("return10", bdr(returnN(recent, 10)));
        values.put("return20", bdr(returnN(recent, 20)));
        values.put("overnightGap", bdr(overnightGap(recent)));
        values.put("intradayReturn", bdr(intradayReturn(recent)));
        values.put("dailyRange", bdr(dailyRange(recent)));
        values.put("closeLocation", bdr(closeLocation(recent)));
        values.put("atr14", bdr(atr14(recent)));
        values.put("volatility5", bdr(volatility(recent, 5)));
        values.put("volatility20", bdr(volatility(recent, 20)));
        values.put("ma5Distance", bdr(maDistance(recent, 5)));
        values.put("ma20Distance", bdr(maDistance(recent, 20)));
        values.put("ma5Slope", bdr(maSlope(recent, 5)));
        values.put("ma20Slope", bdr(maSlope(recent, 20)));
        values.put("rsi14", bdr(rsi14(recent)));
        values.put("drawdown20", bdr(drawdown(recent, 20)));
        values.put("highBreakout20", bdr(highBreakout(recent, 20)));
        values.put("lowBreakdown20", bdr(lowBreakdown(recent, 20)));

        return Optional.of(new GoldOhlcFeatures(values));
    }

    private double bdr(BigDecimal value) {
        return (value != null) ? value.doubleValue() : Double.NaN;
    }

    /** 计算 n 日收益率（%）。 */
    private BigDecimal returnN(List<GoldDailyBar> recent, int n) {
        GoldDailyBar current = recent.get(recent.size() - 1);
        GoldDailyBar past = recent.get(recent.size() - 1 - n);
        if (past.close().signum() == 0) {
            return null;
        }
        return current.close()
                .divide(past.close(), MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算隔夜缺口（%）。 */
    private BigDecimal overnightGap(List<GoldDailyBar> recent) {
        GoldDailyBar current = recent.get(recent.size() - 1);
        GoldDailyBar prev = recent.get(recent.size() - 2);
        if (prev.close().signum() == 0) {
            return null;
        }
        return current.open()
                .divide(prev.close(), MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算日内收益率（%）。 */
    private BigDecimal intradayReturn(List<GoldDailyBar> recent) {
        GoldDailyBar current = recent.get(recent.size() - 1);
        if (current.open().signum() == 0) {
            return null;
        }
        return current.close()
                .divide(current.open(), MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /**
     * 计算日内波幅（%）。
     * 公式：(high - low) / previousClose * 100
     */
    private BigDecimal dailyRange(List<GoldDailyBar> recent) {
        GoldDailyBar current = recent.get(recent.size() - 1);
        GoldDailyBar prev = recent.get(recent.size() - 2);
        if (prev.close().signum() == 0) {
            return null;
        }
        return current.high()
                .subtract(current.low())
                .divide(prev.close(), MC)
                .multiply(HUNDRED);
    }

    /**
     * 计算收盘位置。
     * high == low 时返回 0.5，否则返回 (close - low) / (high - low)。
     */
    private BigDecimal closeLocation(List<GoldDailyBar> recent) {
        GoldDailyBar current = recent.get(recent.size() - 1);
        BigDecimal range = current.high().subtract(current.low());
        if (range.signum() == 0) {
            return new BigDecimal("0.5");
        }
        return current.close()
                .subtract(current.low())
                .divide(range, MC);
    }

    /** 计算 ATR(14)。 */
    private BigDecimal atr14(List<GoldDailyBar> recent) {
        if (recent.size() < 15) {
            return null;
        }
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = recent.size() - 14; i < recent.size(); i++) {
            GoldDailyBar b = recent.get(i);
            GoldDailyBar prev = recent.get(i - 1);
            BigDecimal high = b.high();
            BigDecimal low = b.low();
            BigDecimal prevClose = prev.close();
            BigDecimal tr = high.max(prevClose).subtract(low.min(prevClose));
            trs.add(tr);
        }
        BigDecimal sum = trs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(trs.size()), MC);
    }

    /**
     * 计算 n 日波动率（% 年化）。
     * 使用最近 n+1 根日线产生 n 个日收益率。
     */
    private BigDecimal volatility(List<GoldDailyBar> recent, int n) {
        if (recent.size() < n + 1) {
            return null;
        }
        List<GoldDailyBar> window = recent.subList(
                recent.size() - n - 1, recent.size());
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
        double std = Math.sqrt(variance.doubleValue());
        double annualized = std * Math.sqrt(252) * 100;
        return BigDecimal.valueOf(annualized);
    }

    /** 计算 MA(n) 距离（%）。 */
    private BigDecimal maDistance(List<GoldDailyBar> recent, int n) {
        if (recent.size() < n) {
            return null;
        }
        List<GoldDailyBar> window = recent.subList(
                recent.size() - n, recent.size());
        BigDecimal sum = window.stream()
                .map(GoldDailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ma = sum.divide(new BigDecimal(n), MC);
        BigDecimal current = recent.get(recent.size() - 1).close();
        if (ma.signum() == 0) {
            return null;
        }
        return current
                .divide(ma, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /** 计算 MA(n) 斜率（%）。 */
    private BigDecimal maSlope(List<GoldDailyBar> recent, int n) {
        if (recent.size() < n + 1) {
            return null;
        }
        // 当前 MA
        List<GoldDailyBar> currentWindow = recent.subList(
                recent.size() - n, recent.size());
        BigDecimal currentMa = currentWindow.stream()
                .map(GoldDailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(n), MC);
        // 前一日 MA
        List<GoldDailyBar> prevWindow = recent.subList(
                recent.size() - n - 1, recent.size() - 1);
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

    /** 计算 RSI(14)。 */
    private BigDecimal rsi14(List<GoldDailyBar> recent) {
        if (recent.size() < 15) {
            return null;
        }
        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();
        for (int i = recent.size() - 14; i < recent.size(); i++) {
            GoldDailyBar b = recent.get(i);
            GoldDailyBar prev = recent.get(i - 1);
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

    /**
     * 计算 n 日最大回撤（%）。
     * 需要完整 n 根日线。
     */
    private BigDecimal drawdown(List<GoldDailyBar> recent, int n) {
        if (recent.size() < n) {
            return null;
        }
        List<GoldDailyBar> window = recent.subList(
                recent.size() - n, recent.size());
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

    /**
     * 计算 n 日新高突破（%）。
     * 使用当前日线与此前 n 根日线最高价比较，总共需要 n+1 根。
     */
    private BigDecimal highBreakout(List<GoldDailyBar> recent, int n) {
        if (recent.size() < n + 1) {
            return null;
        }
        GoldDailyBar current = recent.get(recent.size() - 1);
        BigDecimal prevHigh = recent.subList(0, recent.size() - 1).stream()
                .map(GoldDailyBar::high)
                .max(BigDecimal::compareTo)
                .orElse(null);
        if (prevHigh == null || prevHigh.signum() == 0) {
            return null;
        }
        return current.high()
                .divide(prevHigh, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    /**
     * 计算 n 日新低跌破（%）。
     * 使用当前日线与此前 n 根日线最低价比较，总共需要 n+1 根。
     */
    private BigDecimal lowBreakdown(List<GoldDailyBar> recent, int n) {
        if (recent.size() < n + 1) {
            return null;
        }
        GoldDailyBar current = recent.get(recent.size() - 1);
        BigDecimal prevLow = recent.subList(0, recent.size() - 1).stream()
                .map(GoldDailyBar::low)
                .min(BigDecimal::compareTo)
                .orElse(null);
        if (prevLow == null || prevLow.signum() == 0) {
            return null;
        }
        return current.low()
                .divide(prevLow, MC)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }
}
