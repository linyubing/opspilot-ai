package com.opspilot.ai.forecast.learning;

import java.util.Map;
import java.util.Set;

/** 保存预测日当时真实可用的固定数值特征。 */
public record GoldFeatures(Map<String, Double> values) {

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
            // 新增 OHLC 技术特征
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

    /** 创建仅包含 OHLC 技术特征的实例。宏特征使用默认值 0.0。 */
    public static GoldFeatures ohlcOnly(
            String k1, double v1,
            String k2, double v2,
            String k3, double v3,
            String k4, double v4,
            String k5, double v5,
            String k6, double v6,
            String k7, double v7,
            String k8, double v8,
            String k9, double v9,
            String k10, double v10,
            String k11, double v11,
            String k12, double v12,
            String k13, double v13,
            String k14, double v14,
            String k15, double v15,
            String k16, double v16,
            String k17, double v17,
            String k18, double v18,
            String k19, double v19,
            String k20, double v20
    ) {
        java.util.Map<String, Double> map = new java.util.HashMap<>();
        // 宏特征默认值
        map.put("gold_return_1", 0.0);
        map.put("gold_return_5", 0.0);
        map.put("gold_return_20", 0.0);
        map.put("gold_volatility_20", 0.0);
        map.put("intraday_range", 0.0);
        map.put("candle_body", 0.0);
        map.put("close_position", 0.0);
        map.put("real_rate", 0.0);
        map.put("real_rate_bp_1", 0.0);
        map.put("real_rate_bp_5", 0.0);
        map.put("real_rate_bp_20", 0.0);
        map.put("real_rate_age", 0.0);
        map.put("dollar_return_1", 0.0);
        map.put("dollar_return_5", 0.0);
        map.put("dollar_return_20", 0.0);
        map.put("dollar_age", 0.0);
        // OHLC 技术特征
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        map.put(k5, v5);
        map.put(k6, v6);
        map.put(k7, v7);
        map.put(k8, v8);
        map.put(k9, v9);
        map.put(k10, v10);
        map.put(k11, v11);
        map.put(k12, v12);
        map.put(k13, v13);
        map.put(k14, v14);
        map.put(k15, v15);
        map.put(k16, v16);
        map.put(k17, v17);
        map.put(k18, v18);
        map.put(k19, v19);
        map.put(k20, v20);
        return new GoldFeatures(map);
    }
}
