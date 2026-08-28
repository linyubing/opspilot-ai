package com.opspilot.ai.marketdata;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

public interface MarketPriceRepository {

    void saveAll(List<MarketPrice> prices);

    Optional<MarketPrice> findLatest(String symbol);

    List<MarketPrice> findRecent(String symbol, int limit);

    default List<MarketPrice> findRecent(
            String symbol,
            LocalDate endDate,
            int limit
    ) {
        return findRecent(symbol, limit);
    }

    List<MarketPrice> findAfter(String symbol, LocalDate baseDate, int limit);
}
