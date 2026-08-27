package com.opspilot.ai.forecast;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.opspilot.ai.marketdata.MarketPrice;

/**
 * 从真实候选价格中选择第一条周一至周五记录。
 */
@Component
public class NextValidMarketPriceSelector {

    public Optional<MarketPrice> select(List<MarketPrice> candidates) {
        Objects.requireNonNull(candidates, "候选价格不能为空");

        // 节假日不维护静态日历：没有真实行情记录就自然跳到下一条工作日价格。
        return candidates.stream()
                .sorted(Comparator.comparing(MarketPrice::priceDate))
                .filter(price -> isWeekday(price.priceDate().getDayOfWeek()))
                .findFirst();
    }

    private boolean isWeekday(DayOfWeek dayOfWeek) {
        return dayOfWeek != DayOfWeek.SATURDAY
                && dayOfWeek != DayOfWeek.SUNDAY;
    }
}
