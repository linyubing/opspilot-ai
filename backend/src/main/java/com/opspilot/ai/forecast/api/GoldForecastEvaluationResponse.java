package com.opspilot.ai.forecast.api;

import com.opspilot.ai.forecast.DirectionEvaluation;
import com.opspilot.ai.forecast.ForecastVersionEvaluation;
import com.opspilot.ai.forecast.GoldForecastEvaluation;

import java.math.BigDecimal;
import java.util.List;

/** 对外返回黄金预测总体、方向、滚动窗口和版本评测指标。 */
public record GoldForecastEvaluationResponse(
        int totalCount, int pendingCount, int resolvedCount,
        BigDecimal overallAccuracy, DirectionEvaluation bullish,
        DirectionEvaluation neutral, DirectionEvaluation bearish,
        BigDecimal rolling20Accuracy, BigDecimal neutralBaselineAccuracy,
        List<ForecastVersionEvaluation> versions
) {
    public static GoldForecastEvaluationResponse from(GoldForecastEvaluation value) {
        return new GoldForecastEvaluationResponse(
                value.totalCount(), value.pendingCount(), value.resolvedCount(),
                value.overallAccuracy(), value.bullish(), value.neutral(), value.bearish(),
                value.rolling20Accuracy(), value.neutralBaselineAccuracy(), value.versions()
        );
    }
}
