package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import com.opspilot.ai.forecast.SaveGoldForecastResult;

/** 汇总每日数据准备、大模型研究解读和黄金方向预测结果。 */
public record GoldDailyResearchReportResult(
        GoldResearchPreparationResult preparation,
        SaveResearchNarrativeResult narrative,
        SaveGoldForecastResult forecast
) {
}
