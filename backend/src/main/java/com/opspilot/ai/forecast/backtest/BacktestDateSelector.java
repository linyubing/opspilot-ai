package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.marketdata.MarketPrice;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 从完整黄金历史中确定性地选出跨时间分层的回测日期。 */
@Component
public class BacktestDateSelector {

    private static final int HISTORY_SIZE = 20;
    private static final int MAX_SAMPLES = 120;

    public List<LocalDate> select(List<MarketPrice> prices, int samples) {
        checkSamples(samples);

        List<LocalDate> dates = prices.stream()
                .map(MarketPrice::priceDate)
                .distinct()
                .sorted()
                .toList();
        int required = samples + HISTORY_SIZE + 1;
        if (dates.size() < required) {
            throw new BacktestDataInsufficientException(
                    "黄金有效交易日期不足，需要=" + required
                            + "，实际=" + dates.size()
            );
        }

        List<LocalDate> eligible = dates.subList(
                HISTORY_SIZE,
                dates.size() - 1
        );
        if (samples == 1) {
            return List.of(eligible.get((eligible.size() - 1) / 2));
        }

        List<LocalDate> selected = new ArrayList<>(samples);
        for (int index = 0; index < samples; index++) {
            // 把首尾之间的索引等距映射到可用日期，四舍五入可避免长期偏向前段。
            int position = (int) Math.round(
                    index * (eligible.size() - 1D) / (samples - 1D)
            );
            selected.add(eligible.get(position));
        }
        return List.copyOf(selected);
    }

    private void checkSamples(int samples) {
        if (samples < 1 || samples > MAX_SAMPLES) {
            throw new InvalidBacktestRequestException(
                    "samples 必须在 1 到 120 之间，实际=" + samples
            );
        }
    }
}
