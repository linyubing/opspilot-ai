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
        // 旧结果只读保留；不能用今天重新查得的窗口替旧样本补证。
        List<BacktestCase> cases = backtests.results(id, 120).stream()
                .filter(item -> validBase(item, checkedAt)).toList();
        Map<BacktestCase, List<GoldDailyBar>> futureBars = loadBars(cases);
        List<HorizonDiagnostic> result = new ArrayList<>();
        for (int sessions : HORIZONS) {
            result.add(diagnose(id, cases, futureBars, sessions, checkedAt));
        }
        return new HorizonDiagnosticReport(id, List.copyOf(result));
    }

    /** 依据必须在样本保存时已存在，且与实际计分的基准价、日期一致。 */
    private boolean validBase(BacktestCase item, OffsetDateTime checkedAt) {
        if (item == null || item.snapshot() == null || item.createdAt() == null
                || item.createdAt().isAfter(checkedAt) || item.asOfDate() == null
                || item.basePrice() == null || item.basePrice().signum() <= 0) return false;
        var snapshot = item.snapshot();
        return snapshot.input() != null && snapshot.gold() != null
                && item.asOfDate().equals(snapshot.latestGoldDate())
                && snapshot.gold().currentPrice() != null
                && item.basePrice().compareTo(snapshot.gold().currentPrice()) == 0
                && snapshot.input().matches(item.asOfDate(), snapshot.gold(), item.createdAt());
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
