package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 黄金 OHLC 技术特征计算器。
 *
 * <p>与 GoldDatasetBuilder 分离，专注于特征计算逻辑。
 * 所有特征仅使用 asOfDate 当日及以前的真实数据，不允许未来数据泄漏。
 * 数据不足的样本不能进入训练，返回 null 表示特征不可用。
 */
@Service
public class GoldFeatureCalculator {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";

    private final GoldDailyBarRepository repository;

    public GoldFeatureCalculator(GoldDailyBarRepository repository) {
        this.repository = repository;
    }

    /**
     * 计算指定日期的所有 OHLC 技术特征。
     *
     * @param asOfDate 预测时点日期
     * @return 特征对象，数据不足时返回 null
     */
    public GoldFeatures compute(LocalDate asOfDate) {
        List<GoldDailyBar> bars = repository.findAll(SYMBOL, PROVIDER).stream()
                .sorted(Comparator.comparing(GoldDailyBar::priceDate))
                .toList();

        GoldFeatureWindow window = new GoldFeatureWindow(asOfDate, bars);

        // 至少需要 20 根 K 线才能计算所有特征
        if (window.size() < 20) {
            return null;
        }

        return GoldFeatures.ohlcOnly(
                "return1", bdr(window.returnN(1)),
                "return3", bdr(window.returnN(3)),
                "return5", bdr(window.returnN(5)),
                "return10", bdr(window.returnN(10)),
                "return20", bdr(window.returnN(20)),
                "overnightGap", bdr(window.overnightGap()),
                "intradayReturn", bdr(window.intradayReturn()),
                "dailyRange", bdr(window.dailyRange()),
                "closeLocation", bdr(window.closeLocation()),
                "atr14", bdr(window.atr14()),
                "volatility5", bdr(window.volatility(5)),
                "volatility20", bdr(window.volatility(20)),
                "ma5Distance", bdr(window.maDistance(5)),
                "ma20Distance", bdr(window.maDistance(20)),
                "ma5Slope", bdr(window.maSlope(5)),
                "ma20Slope", bdr(window.maSlope(20)),
                "rsi14", bdr(window.rsi14()),
                "drawdown20", bdr(window.drawdown20()),
                "highBreakout20", bdr(window.highBreakout20()),
                "lowBreakdown20", bdr(window.lowBreakdown20())
        );
    }

    private double bdr(BigDecimal value) {
        return Objects.requireNonNull(value, "特征值不能为空（数据不足或计算异常）").doubleValue();
    }
}
