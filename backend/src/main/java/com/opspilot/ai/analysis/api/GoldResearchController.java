package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldDailyResearchReportService;
import com.opspilot.ai.analysis.GoldResearchPreparationService;
import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供黄金研究快照查询、每日数据准备和大模型研究报告接口。
 */
@RestController
@RequestMapping("/api/research/gold")
public class GoldResearchController {

    private final GoldResearchSnapshotService snapshotService;
    private final GoldResearchPreparationService preparationService;
    private final GoldDailyResearchReportService dailyReportService;

    public GoldResearchController(
            GoldResearchSnapshotService snapshotService,
            GoldResearchPreparationService preparationService,
            GoldDailyResearchReportService dailyReportService
    ) {
        this.snapshotService = snapshotService;
        this.preparationService = preparationService;
        this.dailyReportService = dailyReportService;
    }

    @GetMapping("/snapshot")
    public GoldResearchSnapshotResponse snapshot() {
        GoldResearchSnapshot snapshot =
                snapshotService.createSnapshot();

        return GoldResearchSnapshotResponse.from(snapshot);
    }

    @PostMapping("/daily-preparation")
    public GoldResearchPreparationResponse prepareDaily() {
        return GoldResearchPreparationResponse.from(
                preparationService.prepareDaily()
        );
    }

    @PostMapping("/daily-report")
    public GoldDailyResearchReportResponse generateDailyReport() {
        return GoldDailyResearchReportResponse.from(
                dailyReportService.generateDailyReport()
        );
    }
}
