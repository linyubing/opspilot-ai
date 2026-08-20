package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market-data/gold/daily")
public class GoldPriceController {

    private final GoldPriceSyncService syncService;
    private final MarketPriceRepository repository;

    public GoldPriceController(
            GoldPriceSyncService syncService,
            MarketPriceRepository repository
    ) {
        this.syncService = syncService;
        this.repository = repository;
    }

    @PostMapping("/sync")
    public GoldPriceSyncResponse sync() {
        GoldPriceSyncResult result =
                syncService.syncDailyPrices();

        return GoldPriceSyncResponse.from(result);
    }

    @GetMapping("/latest")
    public ResponseEntity<GoldPriceResponse> latest() {
        return repository.findLatest("XAUUSD")
                .map(GoldPriceResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<GoldPriceResponse> recent(
            @RequestParam(defaultValue = "60") int limit
    ) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "limit 必须在 1 到 500 之间"
            );
        }

        return repository.findRecent("XAUUSD", limit)
                .stream()
                .map(GoldPriceResponse::from)
                .toList();
    }
}
