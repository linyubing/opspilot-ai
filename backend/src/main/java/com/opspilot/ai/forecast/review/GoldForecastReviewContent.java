package com.opspilot.ai.forecast.review;

import java.util.List;

/** 保存大模型返回的结构化黄金预测复盘内容。 */
public record GoldForecastReviewContent(
        String summary,
        List<DirectionBiasFinding> directionBiases,
        String recentPerformance,
        List<ForecastVersionFinding> versionFindings,
        List<ForecastImprovementHypothesis> improvementHypotheses,
        List<String> risks,
        String disclaimer
) {
}
