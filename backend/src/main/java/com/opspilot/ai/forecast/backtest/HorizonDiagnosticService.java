package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 诊断研究因子更适合预测未来 1、5 还是 20 个有效交易日。 */
@Service
public class HorizonDiagnosticService {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";
    private static final List<Integer> HORIZONS = List.of(1, 5, 20);

    private final BacktestService backtests;
    private final GoldDailyBarRepository bars;
    private final GoldForecastRule rule;
    private final FactorDiagnosticService factors;
    private final Clock clock;

    public HorizonDiagnosticService(
            BacktestService backtests,
            GoldDailyBarRepository bars,
            GoldForecastRule rule,
            FactorDiagnosticService factors
    ) {
        this(backtests, bars, rule, factors, Clock.systemUTC());
    }

    @Autowired
    public HorizonDiagnosticService(BacktestService backtests, GoldDailyBarRepository bars,
            GoldForecastRule rule, FactorDiagnosticService factors, Clock clock) {
        this.backtests = backtests;
        this.bars = bars;
        this.rule = rule;
        this.factors = factors;
        this.clock = clock;
    }

    public HorizonDiagnosticReport diagnose(UUID id) {
        OffsetDateTime checkedAt = OffsetDateTime.now(clock);
        List<BacktestCase> cases = backtests.results(id, 120);
        Map<BacktestCase, List<GoldDailyBar>> futureBars = loadBars(cases);
        List<HorizonDiagnostic> result = new ArrayList<>();
        for (int sessions : HORIZONS) {
            result.add(diagnose(id, cases, futureBars, sessions, checkedAt));
        }
        return new HorizonDiagnosticReport(id, List.copyOf(result));
    }

    private Map<BacktestCase, List<GoldDailyBar>> loadBars(
            List<BacktestCase> cases
    ) {
        Map<BacktestCase, List<GoldDailyBar>> result = new IdentityHashMap<>();
        for (BacktestCase item : cases) {
            List<GoldDailyBar> future = bars.findAfter(
                    SYMBOL, PROVIDER, item.asOfDate(), 60
            );
            result.put(item, future);
        }
        return result;
    }

    private HorizonDiagnostic diagnose(
            UUID id,
            List<BacktestCase> cases,
            Map<BacktestCase, List<GoldDailyBar>> futureBars,
            int sessions, OffsetDateTime checkedAt
    ) {
        List<BacktestCase> available = new ArrayList<>();
        Map<BacktestCase, ForecastDirection> actual = new IdentityHashMap<>();
        for (BacktestCase item : cases) {
            List<GoldDailyBar> future = futureBars.get(item);
            if (future.size() < sessions) continue;
            // 保留第一至第N根目标原序列，不能删掉中间未确认行后继续计分。
            if (future.subList(0, sessions).stream().anyMatch(bar -> !bar.isConfirmedAt(checkedAt))) continue;

            BigDecimal target = future.get(sessions - 1).close();
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
}
