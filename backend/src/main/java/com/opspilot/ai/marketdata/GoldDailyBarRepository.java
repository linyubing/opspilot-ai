package com.opspilot.ai.marketdata;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 保存和读取带明确开高低收口径的黄金日线。 */
public interface GoldDailyBarRepository {

    void saveAll(List<GoldDailyBar> bars);

    Optional<GoldDailyBar> findLatest(String symbol, String provider);

    List<GoldDailyBar> findRecent(
            String symbol,
            String provider,
            int limit
    );

    List<GoldDailyBar> findRecent(
            String symbol,
            String provider,
            LocalDate endDate,
            int limit
    );

    List<GoldDailyBar> findAll(String symbol, String provider);

    Optional<GoldDailyBar> findNext(
            String symbol,
            String provider,
            LocalDate baseDate
    );
}
