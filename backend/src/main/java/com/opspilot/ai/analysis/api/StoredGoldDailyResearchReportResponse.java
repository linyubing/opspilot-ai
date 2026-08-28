package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.StoredGoldDailyResearchReport;
import com.opspilot.ai.analysis.narrative.api.ResearchNarrativeResponse;
import com.opspilot.ai.forecast.api.GoldForecastResponse;

/** 对外返回同一研究快照下已保存的快照、解读和方向预测。 */
public record StoredGoldDailyResearchReportResponse(
        StoredGoldResearchSnapshotResponse snapshot,
        ResearchNarrativeResponse narrative,
        GoldForecastResponse forecast
) {

    public static StoredGoldDailyResearchReportResponse from(
            StoredGoldDailyResearchReport report
    ) {
        return new StoredGoldDailyResearchReportResponse(
                StoredGoldResearchSnapshotResponse.from(report.snapshot()),
                ResearchNarrativeResponse.from(report.narrative()),
                GoldForecastResponse.from(report.forecast())
        );
    }
}
