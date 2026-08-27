package com.opspilot.ai.analysis.narrative.api;

import com.opspilot.ai.analysis.narrative.GoldResearchNarrativeService;
import com.opspilot.ai.analysis.narrative.SaveResearchNarrativeResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 提供黄金研究大模型解读的生成与历史查询接口。 */
@RestController
@RequestMapping("/api/research/gold/snapshots/{snapshotId}/narratives")
public class GoldResearchNarrativeController {

    private final GoldResearchNarrativeService service;

    public GoldResearchNarrativeController(
            GoldResearchNarrativeService service
    ) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SaveResearchNarrativeResponse> generate(
            @PathVariable UUID snapshotId
    ) {
        SaveResearchNarrativeResult result = service.generate(snapshotId);
        SaveResearchNarrativeResponse response =
                SaveResearchNarrativeResponse.from(result);

        if (result.created()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public List<ResearchNarrativeResponse> history(
            @PathVariable UUID snapshotId
    ) {
        return service.findBySnapshotId(snapshotId)
                .stream()
                .map(ResearchNarrativeResponse::from)
                .toList();
    }
}
