package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供黄金确定性研究快照查询接口，不承担指标计算。
 */
@RestController
@RequestMapping("/api/research/gold")
public class GoldResearchController {

    private final GoldResearchSnapshotService service;

    public GoldResearchController(
            GoldResearchSnapshotService service
    ) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public GoldResearchSnapshotResponse snapshot() {
        GoldResearchSnapshot snapshot =
                service.createSnapshot();

        return GoldResearchSnapshotResponse.from(snapshot);
    }
}
