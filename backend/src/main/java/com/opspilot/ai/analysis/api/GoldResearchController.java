package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchPreparationService;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供黄金研究快照查询和每日数据准备接口，不调用大模型。
 */
@RestController
@RequestMapping("/api/research/gold")
public class GoldResearchController {

    private final GoldResearchSnapshotService snapshotService;
    private final GoldResearchPreparationService preparationService;

    public GoldResearchController(
            GoldResearchSnapshotService snapshotService,
            GoldResearchPreparationService preparationService
    ) {
        this.snapshotService = snapshotService;
        this.preparationService = preparationService;
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
}
