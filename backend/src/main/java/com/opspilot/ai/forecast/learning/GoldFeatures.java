package com.opspilot.ai.forecast.learning;

import java.util.Map;
import java.util.Set;

/** 保存预测日当时真实可用的固定数值特征。 */
public record GoldFeatures(Map<String, Double> values) {

    public static final String VERSION = "gold-features-v2";
    public static final Set<String> NAMES = Set.of(
            "gold_return_1",
            "gold_return_5",
            "gold_return_20",
            "gold_volatility_20",
            "intraday_range",
            "candle_body",
            "close_position",
            "real_rate",
            "real_rate_bp_1",
            "real_rate_bp_5",
            "real_rate_bp_20",
            "real_rate_age",
            "dollar_return_1",
            "dollar_return_5",
            "dollar_return_20",
            "dollar_age",
            // OHLC 技术特征
            "return1",
            "return3",
            "return5",
            "return10",
            "return20",
            "overnightGap",
            "intradayReturn",
            "dailyRange",
            "closeLocation",
            "atr14",
            "volatility5",
            "volatility20",
            "ma5Distance",
            "ma20Distance",
            "ma5Slope",
            "ma20Slope",
            "rsi14",
            "drawdown20",
            "highBreakout20",
            "lowBreakdown20"
    );

    public GoldFeatures {
        values = Map.copyOf(values);
        if (!values.keySet().equals(NAMES)
                || values.values().stream().anyMatch(value -> !Double.isFinite(value))) {
            throw new IllegalArgumentException("黄金特征必须包含 " + NAMES.size() + " 个有限数值");
        }
    }
}
