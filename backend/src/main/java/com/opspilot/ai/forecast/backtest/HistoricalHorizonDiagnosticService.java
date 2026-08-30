package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.forecast.ForecastDirection;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 使用本地真实历史数据扩大样本，诊断因子更适合的预测周期。 */
@Service
public class HistoricalHorizonDiagnosticService {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";
    private static final List<Integer> HORIZONS = List.of(1, 5, 20);

    private final GoldDailyBarRepository bars;
    private final BacktestDateSelector selector;
    private final GoldResearchSnapshotService snapshots;
    private final GoldForecastRule rule;
    private final FactorDiagnosticService factors;
    private final VolatilityDiagnosticService volatility =
            new VolatilityDiagnosticService();

    public HistoricalHorizonDiagnosticService(
            GoldDailyBarRepository bars,
            BacktestDateSelector selector,
            GoldResearchSnapshotService snapshots,
            GoldForecastRule rule,
            FactorDiagnosticService factors
    ) {
        this.bars = bars;
        this.selector = selector;
        this.snapshots = snapshots;
        this.rule = rule;
        this.factors = factors;
    }

    public HistoricalHorizonReport diagnose(int samples) {
        List<GoldDailyBar> history = bars.findAll(SYMBOL, PROVIDER);
        List<LocalDate> dates = selector.selectBars(
                history, samples, BacktestSampleSet.HOLDOUT
        );
        List<HistorySample> selected = loadSamples(dates);
        List<HorizonDiagnostic> result = HORIZONS.stream()
                .map(days -> diagnose(history, selected, days))
                .toList();
        return new HistoricalHorizonReport(samples, result);
    }

    private List<HistorySample> loadSamples(List<LocalDate> dates) {
        List<HistorySample> result = new ArrayList<>();
        for (LocalDate date : dates) {
            try {
                GoldResearchSnapshot snapshot = snapshots.createSnapshot(date);
                result.add(new HistorySample(
                        date, snapshot, snapshot.gold().currentPrice()
                ));
            } catch (InsufficientResearchDataException ignored) {
                // 早期日期缺少三类共同观测值时跳过，不用未来数据补齐。
            }
        }
        return result;
    }

    private HorizonDiagnostic diagnose(
            List<GoldDailyBar> history,
            List<HistorySample> selected,
            int sessions
    ) {
        List<FactorSample> samples = new ArrayList<>();
        List<VolatilitySample> volatilitySamples = new ArrayList<>();
        for (HistorySample item : selected) {
            List<GoldDailyBar> future = history.stream()
                    .filter(bar -> bar.priceDate().isAfter(item.date()))
                    .limit(sessions)
                    .toList();
            if (future.size() < sessions) continue;
            BigDecimal target = future.get(sessions - 1).close();
            BigDecimal change = target.subtract(item.basePrice())
                    .divide(item.basePrice(), 8, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            FactorSample sample = new FactorSample(
                    item.snapshot(), rule.classify(change)
            );
            samples.add(sample);
            volatilitySamples.add(new VolatilitySample(
                    item.date(),
                    item.snapshot().gold().volatility20(),
                    factors.shortReversal(sample),
                    sample.actual()
            ));
        }
        UUID id = UUID.nameUUIDFromBytes(
                ("historical-horizon-" + sessions)
                        .getBytes(StandardCharsets.UTF_8)
        );
        FactorDiagnosticReport report = factors.diagnose(id, samples);
        return new HorizonDiagnostic(
                sessions,
                samples.size(),
                report.factors(),
                volatility.diagnose(volatilitySamples)
        );
    }
    private record HistorySample(
            LocalDate date,
            GoldResearchSnapshot snapshot,
            BigDecimal basePrice
    ) {
    }
}
