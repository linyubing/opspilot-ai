package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldDailyResearchReportResult;
import com.opspilot.ai.analysis.narrative.api.SaveResearchNarrativeResponse;
import com.opspilot.ai.forecast.api.SaveGoldForecastResponse;

/** 对外返回每日数据准备、结构化研究解读和黄金方向预测。 */
public record GoldDailyResearchReportResponse(
        GoldResearchPreparationResponse preparation,
        SaveResearchNarrativeResponse narrative,
        SaveGoldForecastResponse forecast
) {

    public static GoldDailyResearchReportResponse from(
            GoldDailyResearchReportResult result
    ) {
        return new GoldDailyResearchReportResponse(
                GoldResearchPreparationResponse.from(result.preparation()),
                SaveResearchNarrativeResponse.from(result.narrative()),
                SaveGoldForecastResponse.from(result.forecast())
        );
    }
}
