package com.opspilot.ai.analysis;

import java.time.LocalDate;

/**
 * 汇总黄金与实际利率的确定性研究结果及可信边界信息。
 */
public record GoldResearchSnapshot(
        LocalDate analysisDate,
        LocalDate latestGoldDate,
        LocalDate latestRealRateDate,
        LocalDate latestDollarIndexDate,
        GoldReturnMetrics gold,
        RealRateChangeMetrics realRate,
        DollarIndexChangeMetrics dollarIndex,
        ResearchFactorAssessment realRateAssessment,
        ResearchFactorAssessment dollarIndexAssessment,
        String researchVersion,
        String disclaimer
) {

    /** 兼容读取改造前的单因子历史快照。 */
    public GoldResearchSnapshot(
            LocalDate analysisDate,
            LocalDate latestGoldDate,
            LocalDate latestRealRateDate,
            GoldReturnMetrics gold,
            RealRateChangeMetrics realRate,
            ResearchFactorAssessment assessment,
            String disclaimer
    ) {
        this(
                analysisDate,
                latestGoldDate,
                latestRealRateDate,
                null,
                gold,
                realRate,
                null,
                assessment,
                null,
                assessment.ruleVersion(),
                disclaimer
        );
    }

    /** 在 API 合同升级前保持原有单因子访问方式。 */
    public ResearchFactorAssessment assessment() {
        return realRateAssessment;
    }
}
