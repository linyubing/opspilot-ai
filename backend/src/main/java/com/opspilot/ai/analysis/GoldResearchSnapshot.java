package com.opspilot.ai.analysis;

import java.time.LocalDate;

/**
 * 汇总黄金与实际利率的确定性研究结果及可信边界信息。
 */
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
