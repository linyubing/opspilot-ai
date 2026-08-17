package com.opspilot.ai.marketdata;

import java.util.List;

public interface GoldPriceProvider {

    List<MarketPrice> fetchDailyPrices();
}
