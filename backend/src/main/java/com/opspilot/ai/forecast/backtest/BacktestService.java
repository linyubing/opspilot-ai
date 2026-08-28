package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.GoldForecastProperties;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 负责创建和查询黄金历史回测任务。 */
@Service
public class BacktestService {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROMPT_VERSION = "gold-backtest-prompt-v1";
    private static final int HISTORY_SIZE = 20;
    private static final int MAX_SAMPLES = 120;

    private final MarketPriceRepository priceRepo;
    private final BacktestRepository repo;
    private final GoldForecastProperties properties;
    private final Clock clock;

    public BacktestService(
            MarketPriceRepository priceRepo,
            BacktestRepository repo,
            GoldForecastProperties properties,
            Clock clock
    ) {
        this.priceRepo = priceRepo;
        this.repo = repo;
        this.properties = properties;
        this.clock = clock;
    }

    public BacktestTask create(int samples) {
        checkRange(samples, "samples");
        int required = samples + HISTORY_SIZE + 1;
        List<MarketPrice> prices = priceRepo.findRecent(SYMBOL, required);
        List<LocalDate> dates = selectDates(prices, samples, required);

        BacktestTask task = new BacktestTask(
                UUID.randomUUID(),
                dates.get(0),
                dates.get(dates.size() - 1),
                samples,
                properties.modelName(),
                PROMPT_VERSION,
                GoldForecastRule.RULE_VERSION,
                BacktestStatus.CREATED,
                0,
                0,
                0,
                null,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                null,
                null
        );
        return repo.create(task, dates);
    }

    public BacktestTask get(UUID id) {
        return repo.findTask(id).orElseThrow(() ->
                new BacktestNotFoundException("回测任务不存在，编号=" + id)
        );
    }

    public List<BacktestCase> results(UUID id, int limit) {
        checkRange(limit, "limit");
        get(id);
        return repo.findCases(id, limit);
    }

    private List<LocalDate> selectDates(
            List<MarketPrice> prices,
            int samples,
            int required
    ) {
        int actual = prices == null ? 0 : prices.size();
        if (actual < required) {
            throw new BacktestDataInsufficientException(
                    "黄金历史价格不足，需要=" + required + "，实际=" + actual
            );
        }

        List<LocalDate> dates = prices.stream()
                .map(MarketPrice::priceDate)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        if (dates.size() < required) {
            throw new BacktestDataInsufficientException(
                    "黄金有效交易日期不足，需要=" + required
                            + "，实际=" + dates.size()
            );
        }

        /*
         * 最前面的 20 个交易日只用于计算历史指标；
         * 最后一个交易日只用于结算，因此都不能作为预测日期。
         */
        List<LocalDate> eligible = dates.subList(
                HISTORY_SIZE,
                dates.size() - 1
        );
        return eligible.subList(eligible.size() - samples, eligible.size());
    }

    private void checkRange(int value, String name) {
        if (value < 1 || value > MAX_SAMPLES) {
            throw new InvalidBacktestRequestException(
                    name + " 必须在 1 到 120 之间，实际=" + value
            );
        }
    }
}
