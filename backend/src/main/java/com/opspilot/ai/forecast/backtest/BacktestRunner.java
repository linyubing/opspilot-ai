package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GeneratedGoldForecast;
import com.opspilot.ai.forecast.GoldForecastAiUnavailableException;
import com.opspilot.ai.forecast.GoldForecastGateway;
import com.opspilot.ai.forecast.GoldForecastPrompt;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.forecast.NextValidMarketPriceSelector;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 顺序执行黄金历史预测，并使用下一有效交易日价格结算。 */
@Service
public class BacktestRunner {

    private static final String SYMBOL = "XAUUSD";

    private final BacktestRepository repo;
    private final MarketPriceRepository priceRepo;
    private final GoldResearchSnapshotService snapshotService;
    private final BacktestPromptBuilder promptBuilder;
    private final GoldForecastGateway gateway;
    private final GoldForecastValidator validator;
    private final NextValidMarketPriceSelector priceSelector;
    private final GoldForecastRule rule;
    private final Clock clock;

    public BacktestRunner(
            BacktestRepository repo,
            MarketPriceRepository priceRepo,
            GoldResearchSnapshotService snapshotService,
            BacktestPromptBuilder promptBuilder,
            GoldForecastGateway gateway,
            GoldForecastValidator validator,
            NextValidMarketPriceSelector priceSelector,
            GoldForecastRule rule,
            Clock clock
    ) {
        this.repo = repo;
        this.priceRepo = priceRepo;
        this.snapshotService = snapshotService;
        this.promptBuilder = promptBuilder;
        this.gateway = gateway;
        this.validator = validator;
        this.priceSelector = priceSelector;
        this.rule = rule;
        this.clock = clock;
    }

    public void run(UUID id) {
        BacktestTask task = repo.findTask(id).orElseThrow(() ->
                new BacktestNotFoundException("回测任务不存在，编号=" + id)
        );
        Set<LocalDate> done = repo.findDoneDates(id);
        List<MarketPrice> prices = priceRepo.findRecent(
                SYMBOL,
                task.endDate(),
                task.sampleCount()
        );

        for (MarketPrice price : prices.stream()
                .filter(item -> !item.priceDate().isBefore(task.startDate()))
                .filter(item -> !item.priceDate().isAfter(task.endDate()))
                .sorted(Comparator.comparing(MarketPrice::priceDate))
                .toList()) {
            LocalDate date = price.priceDate();
            if (done.contains(date)) {
                continue;
            }

            try {
                repo.saveCase(runOne(task, date));
            } catch (GoldForecastAiUnavailableException exception) {
                repo.fail(id, safeMessage(exception));
                return;
            } catch (RuntimeException exception) {
                repo.recordFailure(id, safeMessage(exception));
            }
        }

        repo.complete(id, now());
    }

    private BacktestCase runOne(BacktestTask task, LocalDate date) {
        UUID caseId = UUID.randomUUID();
        GoldResearchSnapshot snapshot = snapshotService.createSnapshot(date);
        GoldForecastPrompt prompt = promptBuilder.build(caseId, snapshot);
        GeneratedGoldForecast generated = gateway.generate(prompt);
        validator.validate(generated.content());

        MarketPrice nextPrice = priceSelector.select(
                priceRepo.findAfter(SYMBOL, date, 100)
        ).orElseThrow(() -> new BacktestDataInsufficientException(
                "回测日期之后没有可结算的真实价格，日期=" + date
        ));

        BigDecimal basePrice = snapshot.gold().currentPrice();
        BigDecimal actualReturn = nextPrice.referencePrice()
                .subtract(basePrice)
                .divide(basePrice, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(6, RoundingMode.HALF_UP);
        ForecastDirection actual = rule.classify(actualReturn);
        boolean hit = generated.content().direction() == actual;

        return new BacktestCase(
                caseId,
                task.id(),
                date,
                snapshot,
                basePrice,
                generated.content().direction(),
                generated.content().reasoning(),
                generated.content().invalidationConditions(),
                nextPrice.priceDate(),
                nextPrice.referencePrice(),
                actualReturn,
                actual,
                hit,
                generated.modelName(),
                prompt.version(),
                prompt.sha256(),
                task.ruleVersion(),
                generated.rawResponse(),
                now()
        );
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
