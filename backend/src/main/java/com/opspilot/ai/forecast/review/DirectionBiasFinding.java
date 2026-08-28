package com.opspilot.ai.forecast.review;

/** 保存 AI 复盘识别出的单个预测方向偏差。 */
public record DirectionBiasFinding(
        String direction,
        String observation,
        String evidence
) {
}
