package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRecordingService;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.InvalidResearchHistoryRequestException;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供黄金研究快照正式留痕与历史查询接口，不承担指标计算和 SQL。
 */
@RestController
@RequestMapping("/api/research/gold/snapshots")
public class GoldResearchSnapshotHistoryController {

    private final GoldResearchSnapshotRecordingService recordingService;
    private final GoldResearchSnapshotRepository repository;

    public GoldResearchSnapshotHistoryController(
            GoldResearchSnapshotRecordingService recordingService,
            GoldResearchSnapshotRepository repository
    ) {
        this.recordingService = recordingService;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<SaveGoldResearchSnapshotResponse> record() {
        SaveGoldResearchSnapshotResult result =
                recordingService.recordCurrentSnapshot();
        SaveGoldResearchSnapshotResponse response =
                SaveGoldResearchSnapshotResponse.from(result);

        if (result.created()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping
    public List<StoredGoldResearchSnapshotResponse> history(
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new InvalidResearchHistoryRequestException(
                    "limit 必须在 1 到 100 之间"
            );
        }

        return repository.findRecent(limit)
                .stream()
                .map(StoredGoldResearchSnapshotResponse::from)
                .toList();
    }
}
