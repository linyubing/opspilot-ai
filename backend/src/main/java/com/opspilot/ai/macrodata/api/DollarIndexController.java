package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.macrodata.DollarIndexFreshnessEvaluator;
import com.opspilot.ai.macrodata.DollarIndexNotFoundException;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.DollarIndexSyncService;
import com.opspilot.ai.macrodata.InvalidMacroDataRequestException;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供广义美元指数同步、最新值与历史查询接口。 */
@RestController
@RequestMapping("/api/macro-data/dollar-index")
public class DollarIndexController {

    private static final String SERIES_ID = "DTWEXBGS";

    private final DollarIndexSyncService syncService;
    private final MacroObservationRepository repository;
    private final DollarIndexFreshnessEvaluator freshnessEvaluator;

    public DollarIndexController(
            DollarIndexSyncService syncService,
            MacroObservationRepository repository,
            DollarIndexFreshnessEvaluator freshnessEvaluator
    ) {
        this.syncService = syncService;
        this.repository = repository;
        this.freshnessEvaluator = freshnessEvaluator;
    }

    @PostMapping("/sync")
    public DollarIndexSyncResponse sync() {
        DollarIndexSyncResult result = syncService.syncDailyObservations();
        return DollarIndexSyncResponse.from(result);
    }

    @GetMapping("/latest")
    public DollarIndexResponse latest() {
        return repository.findLatest(SERIES_ID)
                .map(observation -> DollarIndexResponse.from(
                        observation,
                        freshnessEvaluator.evaluate(observation.observationDate())
                ))
                .orElseThrow(() -> new DollarIndexNotFoundException(
                        "尚无广义美元指数数据"
                ));
    }

    @GetMapping("/observations")
    public List<DollarIndexResponse> recent(
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (limit < 1 || limit > 500) {
            throw new InvalidMacroDataRequestException(
                    "limit 必须在 1 到 500 之间"
            );
        }

        return repository.findRecent(SERIES_ID, limit)
                .stream()
                .map(observation -> DollarIndexResponse.from(
                        observation,
                        freshnessEvaluator.evaluate(observation.observationDate())
                ))
                .toList();
    }
}
