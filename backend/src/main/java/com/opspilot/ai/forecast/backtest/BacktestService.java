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
import java.util.List;
import java.util.UUID;

/** 负责创建和查询黄金历史回测任务。 */
@Service
public class BacktestService {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROMPT_VERSION = "gold-backtest-prompt-v1";
    private static final int MAX_SAMPLES = 120;

    private final MarketPriceRepository priceRepo;
    private final BacktestRepository repo;
    private final BacktestDateSelector selector;
    private final GoldForecastProperties properties;
    private final Clock clock;

    public BacktestService(
            MarketPriceRepository priceRepo,
            BacktestRepository repo,
            BacktestDateSelector selector,
            GoldForecastProperties properties,
            Clock clock
    ) {
        this.priceRepo = priceRepo;
        this.repo = repo;
        this.selector = selector;
        this.properties = properties;
        this.clock = clock;
    }

    public BacktestTask create(int samples) {
        checkRange(samples, "samples");
        List<MarketPrice> prices = priceRepo.findAll(SYMBOL);
        List<LocalDate> dates = selector.select(prices, samples);

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

    public List<LocalDate> samples(UUID id) {
        get(id);
        return repo.findSampleDates(id);
    }

    private void checkRange(int value, String name) {
        if (value < 1 || value > MAX_SAMPLES) {
            throw new InvalidBacktestRequestException(
                    name + " 必须在 1 到 120 之间，实际=" + value
            );
        }
    }
}
