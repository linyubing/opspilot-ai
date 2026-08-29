package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.MarketPrice;
import com.opspilot.ai.marketdata.MarketPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 诊断研究因子更适合预测未来 1、5 还是 20 个有效交易日。 */
@Service
public class HorizonDiagnosticService {

    private static final String SYMBOL = "XAUUSD";
    private static final List<Integer> HORIZONS = List.of(1, 5, 20);

    private final BacktestService backtests;
    private final MarketPriceRepository prices;
    private final GoldForecastRule rule;
    private final FactorDiagnosticService factors;

    public HorizonDiagnosticService(
            BacktestService backtests,
            MarketPriceRepository prices,
            GoldForecastRule rule,
            FactorDiagnosticService factors
    ) {
        this.backtests = backtests;
        this.prices = prices;
        this.rule = rule;
        this.factors = factors;
    }

    public HorizonDiagnosticReport diagnose(UUID id) {
        List<BacktestCase> cases = backtests.results(id, 120);
        Map<BacktestCase, List<MarketPrice>> futurePrices = loadPrices(cases);
        List<HorizonDiagnostic> result = new ArrayList<>();
        for (int sessions : HORIZONS) {
            result.add(diagnose(id, cases, futurePrices, sessions));
        }
        return new HorizonDiagnosticReport(id, List.copyOf(result));
    }

    private Map<BacktestCase, List<MarketPrice>> loadPrices(
            List<BacktestCase> cases
    ) {
        Map<BacktestCase, List<MarketPrice>> result = new IdentityHashMap<>();
        for (BacktestCase item : cases) {
            List<MarketPrice> future = prices.findAfter(
                            SYMBOL, item.asOfDate(), 60
                    ).stream()
                    .filter(price -> weekday(price.priceDate().getDayOfWeek()))
                    .sorted(Comparator.comparing(MarketPrice::priceDate))
                    .toList();
            result.put(item, future);
        }
        return result;
    }

    private HorizonDiagnostic diagnose(
            UUID id,
            List<BacktestCase> cases,
            Map<BacktestCase, List<MarketPrice>> futurePrices,
            int sessions
    ) {
        List<BacktestCase> available = new ArrayList<>();
        Map<BacktestCase, ForecastDirection> actual = new IdentityHashMap<>();
        for (BacktestCase item : cases) {
            List<MarketPrice> future = futurePrices.get(item);
            if (future.size() < sessions) continue;

            BigDecimal target = future.get(sessions - 1).referencePrice();
            BigDecimal change = target.subtract(item.basePrice())
                    .divide(item.basePrice(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            available.add(item);
            actual.put(item, rule.classify(change));
        }

        FactorDiagnosticReport report = factors.diagnose(
                id, available, actual::get
        );
        return new HorizonDiagnostic(
                sessions,
                available.size(),
                report.factors()
        );
    }

    private boolean weekday(DayOfWeek day) {
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
