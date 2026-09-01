package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.GoldDailyBar;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 从完整黄金历史中确定性地选出跨时间分层的回测日期。
 *
 * <p>时间分区规则：
 * 1. 排除前 21 根特征准备期
 * 2. 排除最后一根没有未来目标价格的日期
 * 3. 剩余 eligible 日期严格升序
 * 4. 前 80% 为开发区间（DEFAULT）
 * 5. 后 20% 为最终留出区间（HOLDOUT）
 * 6. RECENT 表示全部 eligible 中最近的日期
 */
@Component
public class BacktestDateSelector {

    private static final int WARMUP_SIZE = 21;
    private static final int MAX_SAMPLES = 120;

    public List<LocalDate> select(List<MarketPrice> prices, int samples) {
        return select(prices, samples, BacktestSampleSet.DEFAULT);
    }

    public List<LocalDate> select(
            List<MarketPrice> prices,
            int samples,
            BacktestSampleSet sampleSet
    ) {
        List<LocalDate> dates = prices.stream()
                .map(MarketPrice::priceDate)
                .distinct()
                .sorted()
                .toList();
        return selectDates(dates, samples, sampleSet);
    }

    /** 从真实黄金 OHLC 日线中选择回测日期。 */
    public List<LocalDate> selectBars(
            List<GoldDailyBar> bars,
            int samples,
            BacktestSampleSet sampleSet
    ) {
        List<LocalDate> dates = bars.stream()
                .map(GoldDailyBar::priceDate)
                .distinct()
                .sorted()
                .toList();
        return selectDates(dates, samples, sampleSet);
    }

    private List<LocalDate> selectDates(
            List<LocalDate> dates,
            int samples,
            BacktestSampleSet sampleSet
    ) {
        checkSamples(samples);
        // 至少需要 warmup + 1 根特征准备期 + 1 根未来目标
        int required = WARMUP_SIZE + 2;
        if (dates.size() < required) {
            throw new BacktestDataInsufficientException(
                    "黄金有效交易日期不足，需要至少=" + required
                            + "，实际=" + dates.size()
            );
        }

        // 排除前 warmup 根和最后一根（无未来目标）
        List<LocalDate> eligible = dates.subList(
                WARMUP_SIZE,
                dates.size() - 1
        );
        if (eligible.isEmpty()) {
            throw new BacktestDataInsufficientException(
                    "排除预热期后无可用日期"
            );
        }

        // 按时间切分：前 80% 为开发集，后 20% 为留出集
        int splitIndex = (int) Math.floor(eligible.size() * 0.8);
        List<LocalDate> development = eligible.subList(0, splitIndex);
        List<LocalDate> holdout = eligible.subList(splitIndex, eligible.size());

        return switch (sampleSet) {
            case DEFAULT -> {
                if (development.size() < samples) {
                    throw new BacktestDataInsufficientException(
                            "开发集日期不足，需要=" + samples
                                    + "，实际=" + development.size()
                    );
                }
                yield stratified(development, samples);
            }
            case HOLDOUT -> {
                if (holdout.size() < samples) {
                    throw new BacktestDataInsufficientException(
                            "留出集日期不足，需要=" + samples
                                    + "，实际=" + holdout.size()
                    );
                }
                yield stratified(holdout, samples);
            }
            case RECENT -> {
                if (eligible.size() < samples) {
                    throw new BacktestDataInsufficientException(
                            "eligible 日期不足，需要=" + samples
                                    + "，实际=" + eligible.size()
                    );
                }
                yield List.copyOf(eligible.subList(
                        eligible.size() - samples,
                        eligible.size()
                ));
            }
        };
    }

    private List<LocalDate> stratified(List<LocalDate> dates, int samples) {
        if (samples == 1) {
            return List.of(dates.get((dates.size() - 1) / 2));
        }

        List<LocalDate> selected = new ArrayList<>(samples);
        for (int index = 0; index < samples; index++) {
            int position = (int) Math.round(
                    index * (dates.size() - 1D) / (samples - 1D)
            );
            selected.add(dates.get(position));
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
