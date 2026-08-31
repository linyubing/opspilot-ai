package com.opspilot.ai.forecast.learning;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import com.opspilot.ai.analysis.InsufficientResearchDataException;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldDailyBarRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用预测时点真实可见的数据构建黄金监督学习样本。 */
@Service
public class GoldDatasetBuilder {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";
    private static final int HISTORY = 20;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final GoldDailyBarRepository repository;
    private final GoldResearchSnapshotService snapshots;
    private final GoldForecastRule rule;

    public GoldDatasetBuilder(
            GoldDailyBarRepository repository,
            GoldResearchSnapshotService snapshots,
            GoldForecastRule rule
    ) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.rule = rule;
    }

    public GoldDataset build(ForecastHorizon horizon) {
        Objects.requireNonNull(horizon, "预测周期不能为空");
        List<GoldDailyBar> bars = repository.findAll(SYMBOL, PROVIDER).stream()
                .sorted(Comparator.comparing(GoldDailyBar::priceDate))
                .toList();

        List<GoldSample> samples = new ArrayList<>();
        int skipped = 0;
        for (int i = HISTORY; i + horizon.sessions() < bars.size(); i++) {
            GoldDailyBar base = bars.get(i);
            GoldDailyBar target = bars.get(i + horizon.sessions());
            try {
                GoldResearchSnapshot snapshot = snapshots.createSnapshot(base.priceDate());
                validateDates(snapshot);
                samples.add(new GoldSample(
                        base.priceDate(),
                        target.priceDate(),
                        horizon,
                        features(base, snapshot),
                        rule.classify(change(target.close(), base.close()))
                ));
            } catch (InsufficientResearchDataException exception) {
                // 真实因子缺失时直接弃用该样本，绝不补零或读取未来数据。
                skipped++;
            }
        }
        return new GoldDataset(samples, skipped);
    }

    private GoldFeatures features(
            GoldDailyBar bar,
            GoldResearchSnapshot snapshot
    ) {
        Map<String, Double> values = new HashMap<>();
        values.put("gold_return_1", number(snapshot.gold().return1()));
        values.put("gold_return_5", number(snapshot.gold().return5()));
        values.put("gold_return_20", number(snapshot.gold().return20()));
        values.put("gold_volatility_20", number(snapshot.gold().volatility20()));
        values.put("intraday_range", number(change(bar.high(), bar.low(), bar.open())));
        values.put("candle_body", number(change(bar.close(), bar.open(), bar.open())));
        values.put("close_position", closePosition(bar));
        values.put("real_rate", number(snapshot.realRate().currentRate()));
        values.put("real_rate_bp_1", number(snapshot.realRate().basisPointChange1()));
        values.put("real_rate_bp_5", number(snapshot.realRate().basisPointChange5()));
        values.put("real_rate_bp_20", number(snapshot.realRate().basisPointChange20()));
        values.put("real_rate_age", (double) ChronoUnit.DAYS.between(
                snapshot.latestRealRateDate(), snapshot.analysisDate()));
        values.put("dollar_return_1", number(snapshot.dollarIndex().return1()));
        values.put("dollar_return_5", number(snapshot.dollarIndex().return5()));
        values.put("dollar_return_20", number(snapshot.dollarIndex().return20()));
        values.put("dollar_age", (double) ChronoUnit.DAYS.between(
                snapshot.latestDollarIndexDate(), snapshot.analysisDate()));
        return new GoldFeatures(values);
    }

    private void validateDates(GoldResearchSnapshot snapshot) {
        if (snapshot.latestRealRateDate().isAfter(snapshot.analysisDate())
                || snapshot.latestDollarIndexDate().isAfter(snapshot.analysisDate())) {
            throw new IllegalStateException("宏观特征不能来自分析日期之后");
        }
    }

    private double closePosition(GoldDailyBar bar) {
        BigDecimal range = bar.high().subtract(bar.low());
        if (range.signum() == 0) {
            return 0.5;
        }
        return bar.close()
                .subtract(bar.low())
                .divide(range, MathContext.DECIMAL128)
                .doubleValue();
    }

    private BigDecimal change(BigDecimal current, BigDecimal base) {
        return current
                .divide(base, MathContext.DECIMAL128)
                .subtract(BigDecimal.ONE)
                .multiply(HUNDRED);
    }

    private BigDecimal change(
            BigDecimal high,
            BigDecimal low,
            BigDecimal base
    ) {
        return high.subtract(low)
                .divide(base, MathContext.DECIMAL128)
                .multiply(HUNDRED);
    }

    private double number(BigDecimal value) {
        return Objects.requireNonNull(value, "特征值不能为空").doubleValue();
    }
}
