package com.opspilot.ai.marketdata.api;

import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Clock;
import java.time.OffsetDateTime;

/** 提供黄金 OHLC 日线的同步和最新行情查询接口。 */
@RestController
@RequestMapping("/api/market-data/gold/daily-bars")
public class GoldDailyBarController {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";

    private final GoldDailyBarSyncService sync;
    private final GoldDailyBarRepository repository;
    private final Clock clock;

    public GoldDailyBarController(
            GoldDailyBarSyncService sync,
            GoldDailyBarRepository repository
    ) {
        this(sync, repository, Clock.systemUTC());
    }

    @Autowired
    public GoldDailyBarController(GoldDailyBarSyncService sync,
            GoldDailyBarRepository repository, Clock clock) {
        this.sync = sync;
        this.repository = repository;
        this.clock = clock;
    }

    @PostMapping("/sync")
    public GoldDailyBarSyncResponse sync() {
        return GoldDailyBarSyncResponse.from(sync.sync());
    }

    /** 只刷新最新确认日，不表示历史完整或预测模型通过验收。 */
    @PostMapping("/sync-latest")
    public GoldDailyBarSyncResponse syncLatest() {
        return GoldDailyBarSyncResponse.from(sync.syncLatest());
    }

    @GetMapping("/latest")
    public ResponseEntity<GoldDailyBarResponse> latest() {
        OffsetDateTime asOf = OffsetDateTime.now(clock);
        return repository.findLatest(SYMBOL, PROVIDER)
                // 不回退到更旧一根冒充最新价；未确认时明确没有正式行情。
                .filter(bar -> bar.isConfirmedAt(asOf))
                .map(GoldDailyBarResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
