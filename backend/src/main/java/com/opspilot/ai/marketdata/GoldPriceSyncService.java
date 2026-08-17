package com.opspilot.ai.marketdata;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;

@Service
public class GoldPriceSyncService {

    private final GoldPriceProvider provider;
    private final MarketPriceRepository repository;

    public GoldPriceSyncService(
            GoldPriceProvider provider,
            MarketPriceRepository repository
    ) {
        this.provider = provider;
        this.repository = repository;
    }

    public GoldPriceSyncResult syncDailyPrices() {
        List<MarketPrice> receivedPrices =
                provider.fetchDailyPrices();

        if (receivedPrices == null
                || receivedPrices.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "黄金历史价格为空"
            );
        }

        /*
         * Alpha Vantage 的黄金历史接口可能返回周末价格。
         * 第一版预测只使用有效工作日，因此这里统一排除周六和周日。
         */
        List<MarketPrice> validPrices = receivedPrices.stream()
                .filter(this::isWeekday)
                .sorted(Comparator.comparing(
                        MarketPrice::priceDate
                ))
                .toList();

        if (validPrices.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "黄金历史价格没有有效工作日数据"
            );
        }

        repository.saveAll(validPrices);

        MarketPrice latestPrice =
                validPrices.get(validPrices.size() - 1);

        return new GoldPriceSyncResult(
                receivedPrices.size(),
                validPrices.size(),
                receivedPrices.size() - validPrices.size(),
                latestPrice.priceDate()
        );
    }

    /**
     * 周一到周五属于系统认可的有效工作日。
     * 节假日不在这里硬编码，而是根据数据源是否返回有效数据判断。
     */
    private boolean isWeekday(MarketPrice price) {
        DayOfWeek dayOfWeek =
                price.priceDate().getDayOfWeek();

        return dayOfWeek != DayOfWeek.SATURDAY
                && dayOfWeek != DayOfWeek.SUNDAY;
    }
}