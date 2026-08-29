package com.opspilot.ai.forecast.backtest.review;

import java.util.List;

/** 保存 AI 从真实回测错误中识别出的单个模式。 */
public record BacktestErrorPattern(
        String category,
        String observation,
        List<String> evidence,
        String improvement,
        String validationMethod
) {
}
