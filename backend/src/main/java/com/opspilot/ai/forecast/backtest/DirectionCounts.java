package com.opspilot.ai.forecast.backtest;

/** 记录某个真实方向被预测为上涨、中性和下跌的次数。 */
public record DirectionCounts(
        int bullish,
        int neutral,
        int bearish
) {
}
