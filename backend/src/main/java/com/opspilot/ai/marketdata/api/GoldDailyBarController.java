package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供黄金 OHLC 日线的同步和最新行情查询接口。 */
@RestController
@RequestMapping("/api/market-data/gold/daily-bars")
public class GoldDailyBarController {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";

    private final GoldDailyBarSyncService sync;
    private final GoldDailyBarRepository repository;

    public GoldDailyBarController(
            GoldDailyBarSyncService sync,
            GoldDailyBarRepository repository
    ) {
        this.sync = sync;
        this.repository = repository;
    }

    @PostMapping("/sync")
    public GoldDailyBarSyncResponse sync() {
        return GoldDailyBarSyncResponse.from(sync.sync());
    }

    @GetMapping("/latest")
    public ResponseEntity<GoldDailyBarResponse> latest() {
        return repository.findLatest(SYMBOL, PROVIDER)
                .map(GoldDailyBarResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
