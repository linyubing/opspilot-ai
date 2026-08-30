package com.opspilot.ai.marketdata;

import java.util.List;
import java.util.Optional;

/** 保存和读取带明确开高低收口径的黄金日线。 */
public interface GoldDailyBarRepository {

    void saveAll(List<GoldDailyBar> bars);

    Optional<GoldDailyBar> findLatest(String symbol, String provider);
}
