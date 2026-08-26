package com.opspilot.ai.macrodata.api;

import com.opspilot.ai.macrodata.InvalidMacroDataRequestException;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/macro-data/real-rate")
public class RealRateController {

    private static final String SERIES_ID = "DFII10";

    private final RealRateSyncService syncService;
    private final MacroObservationRepository repository;

    public RealRateController(
            RealRateSyncService syncService,
            MacroObservationRepository repository
    ) {
        this.syncService = syncService;
        this.repository = repository;
    }

    @PostMapping("/sync")
    public RealRateSyncResponse sync() {
        RealRateSyncResult result =
                syncService.syncDailyObservations();

        return RealRateSyncResponse.from(result);
    }

    @GetMapping("/latest")
    public ResponseEntity<RealRateResponse> latest() {
        return repository.findLatest(SERIES_ID)
                .map(RealRateResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<RealRateResponse> recent(
            @RequestParam(defaultValue = "60") int limit
    ) {
        if (limit < 1 || limit > 500) {
            throw new InvalidMacroDataRequestException(
                    "limit 必须在 1 到 500 之间"
            );
        }

        return repository.findRecent(SERIES_ID, limit)
                .stream()
                .map(RealRateResponse::from)
                .toList();
    }
}
