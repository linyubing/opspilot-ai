package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldDailyResearchReportResult;
import com.opspilot.ai.analysis.narrative.api.SaveResearchNarrativeResponse;

/** 对外返回每日数据准备结果和结构化黄金研究解读。 */
public record GoldDailyResearchReportResponse(
        GoldResearchPreparationResponse preparation,
        SaveResearchNarrativeResponse narrative
) {

    public static GoldDailyResearchReportResponse from(
            GoldDailyResearchReportResult result
    ) {
        return new GoldDailyResearchReportResponse(
                GoldResearchPreparationResponse.from(result.preparation()),
                SaveResearchNarrativeResponse.from(result.narrative())
        );
    }
}
