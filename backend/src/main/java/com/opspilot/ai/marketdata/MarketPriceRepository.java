package com.opspilot.ai.marketdata;

import java.util.List;
import java.util.Optional;

public interface MarketPriceRepository {

    void saveAll(List<MarketPrice> prices);

    Optional<MarketPrice> findLatest(String symbol);

    List<MarketPrice> findRecent(String symbol, int limit);
}
