package com.opspilot.ai.marketdata;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;

/** 从 Twelve Data 同步并保存有效工作日的黄金 OHLC 日线。 */
@Service
public class GoldDailyBarSyncService {

    private final TwelveDataGoldBarProvider provider;
    private final GoldDailyBarRepository repository;

    public GoldDailyBarSyncService(
            TwelveDataGoldBarProvider provider,
            GoldDailyBarRepository repository
    ) {
        this.provider = provider;
        this.repository = repository;
    }

    public GoldDailyBarSyncResult sync() {
        List<GoldDailyBar> received = provider.fetchDailyBars();
        if (received == null || received.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金 OHLC 日线为空"
            );
        }

        List<GoldDailyBar> valid = received.stream()
                .filter(this::weekday)
                .sorted(Comparator.comparing(GoldDailyBar::priceDate))
                .toList();
        if (valid.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 没有有效工作日黄金日线"
            );
        }

        repository.saveAll(valid);
        return new GoldDailyBarSyncResult(
                received.size(),
                valid.size(),
                received.size() - valid.size(),
                valid.getLast().priceDate()
        );
    }

    private boolean weekday(GoldDailyBar bar) {
        DayOfWeek day = bar.priceDate().getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
