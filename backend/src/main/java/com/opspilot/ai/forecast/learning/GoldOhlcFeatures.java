package com.opspilot.ai.forecast.learning;

import java.util.Map;
import java.util.Set;

/**
 * 保存预测日当时真实可用的 OHLC 技术特征。
 *
 * <p>只包含 20 个 OHLC 技术特征，不包含宏观因子。
 * 数据不足时整条样本必须跳过，不允许补零。
 */
public record GoldOhlcFeatures(Map<String, Double> values) {

    public static final Set<String> NAMES = Set.of(
            "return1", "return3", "return5", "return10", "return20",
            "overnightGap", "intradayReturn", "dailyRange", "closeLocation",
            "atr14", "volatility5", "volatility20",
            "ma5Distance", "ma20Distance", "ma5Slope", "ma20Slope",
            "rsi14", "drawdown20", "highBreakout20", "lowBreakdown20"
    );

    public GoldOhlcFeatures {
        values = Map.copyOf(values);
        if (!values.keySet().equals(NAMES)
                || values.values().stream().anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException("OHLC 特征必须包含 " + NAMES.size() + " 个有限数值");
        }
    }
}
