package com.opspilot.ai.analysis.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opspilot.ai.analysis.DollarIndexChangeMetrics;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldReturnMetrics;
import com.opspilot.ai.analysis.RealRateChangeMetrics;
import com.opspilot.ai.analysis.ResearchFactorAssessment;

import java.time.LocalDate;

/**
 * 黄金研究快照的 HTTP 响应，不重新计算任何指标。
 */
public record GoldResearchSnapshotResponse(
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate analysisDate,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate latestGoldDate,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate latestRealRateDate,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate latestDollarIndexDate,
        GoldReturnMetrics gold,
        RealRateChangeMetrics realRate,
        DollarIndexChangeMetrics dollarIndex,
        ResearchFactorAssessment realRateAssessment,
        ResearchFactorAssessment dollarIndexAssessment,
        String researchVersion,
        String disclaimer
) {

    public static GoldResearchSnapshotResponse from(
            GoldResearchSnapshot snapshot
    ) {
        return new GoldResearchSnapshotResponse(
                snapshot.analysisDate(),
                snapshot.latestGoldDate(),
                snapshot.latestRealRateDate(),
                snapshot.latestDollarIndexDate(),
                snapshot.gold(),
                snapshot.realRate(),
                snapshot.dollarIndex(),
                snapshot.realRateAssessment(),
                snapshot.dollarIndexAssessment(),
                snapshot.researchVersion(),
                snapshot.disclaimer()
        );
    }
}
