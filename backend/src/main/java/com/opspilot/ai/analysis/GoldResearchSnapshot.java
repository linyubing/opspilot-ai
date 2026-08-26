package com.opspilot.ai.analysis;

import java.time.LocalDate;

public record GoldResearchSnapshot(
        LocalDate analysisDate,
        LocalDate latestGoldDate,
        LocalDate latestRealRateDate,
        GoldReturnMetrics gold,
        RealRateChangeMetrics realRate,
        ResearchFactorAssessment assessment,
        String disclaimer
) {
}
