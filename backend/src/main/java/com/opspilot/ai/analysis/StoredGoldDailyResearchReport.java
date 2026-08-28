package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.analysis.narrative.StoredResearchNarrative;
import com.opspilot.ai.forecast.StoredGoldDirectionForecast;

/** 保存从数据库组装出的同一快照完整黄金日报。 */
public record StoredGoldDailyResearchReport(
        StoredGoldResearchSnapshot snapshot,
        StoredResearchNarrative narrative,
        StoredGoldDirectionForecast forecast
) {
}
