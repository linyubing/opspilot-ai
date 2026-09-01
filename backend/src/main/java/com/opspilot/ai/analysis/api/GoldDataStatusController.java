package com.opspilot.ai.analysis.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供黄金研究输入数据的健康状态接口。
 */
@RestController
@RequestMapping("/api/research/gold")
public class GoldDataStatusController {

    private final GoldDataStatusService dataStatusService;

    public GoldDataStatusController(GoldDataStatusService dataStatusService) {
        this.dataStatusService = dataStatusService;
    }

    @GetMapping("/data-status")
    public GoldDataStatus dataStatus() {
        return dataStatusService.latest();
    }
}
