package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.narrative.GoldResearchNarrativeService;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 编排真实研究数据准备和黄金研究解读生成。 */
@Service
public class GoldDailyResearchReportService {

    private final GoldResearchPreparationService preparationService;
    private final GoldResearchNarrativeService narrativeService;

    public GoldDailyResearchReportService(
            GoldResearchPreparationService preparationService,
            GoldResearchNarrativeService narrativeService
    ) {
        this.preparationService = preparationService;
        this.narrativeService = narrativeService;
    }

    public GoldDailyResearchReportResult generateDailyReport() {
        // 先同步三类真实数据并保存正式研究快照。
        GoldResearchPreparationResult preparation =
                preparationService.prepareDaily();

        // 大模型必须基于刚刚保存的正式快照生成解读。
        UUID snapshotId = preparation.snapshot().record().id();
        SaveResearchNarrativeResult narrative =
                narrativeService.generate(snapshotId);

        return new GoldDailyResearchReportResult(preparation, narrative);
    }
}
