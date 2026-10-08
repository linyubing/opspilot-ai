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

    /** 最新行情与全历史同步分离；旧历史异常仍由原同步入口报告。 */
    public GoldDailyBarSyncResult syncLatest() {
        GoldDailyBar bar = provider.fetchLatestBar();
        if (!weekday(bar)) {
            throw new MarketDataUnavailableException("Twelve Data 最新确认日不是有效工作日");
        }
        repository.findLatest(bar.symbol(), bar.provider()).ifPresent(old -> {
            if (old.priceDate().isAfter(bar.priceDate())) {
                throw new MarketDataUnavailableException("Twelve Data 最新确认日落后于已保存行情");
            }
            if (old.priceDate().equals(bar.priceDate())
                    && (old.open().compareTo(bar.open()) != 0 || old.high().compareTo(bar.high()) != 0
                    || old.low().compareTo(bar.low()) != 0 || old.close().compareTo(bar.close()) != 0)) {
                throw new MarketDataUnavailableException("Twelve Data 最新价格发生修订，拒绝覆盖已保存行情");
            }
        });
        repository.saveAll(List.of(bar));
        return new GoldDailyBarSyncResult(1, 1, 0, bar.priceDate());
    }

    private boolean weekday(GoldDailyBar bar) {
        DayOfWeek day = bar.priceDate().getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
