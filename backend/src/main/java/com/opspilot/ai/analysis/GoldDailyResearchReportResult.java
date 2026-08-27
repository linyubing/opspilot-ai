package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;

/** 汇总每日数据准备和大模型研究解读结果。 */
public record GoldDailyResearchReportResult(
        GoldResearchPreparationResult preparation,
        SaveResearchNarrativeResult narrative
) {
}
