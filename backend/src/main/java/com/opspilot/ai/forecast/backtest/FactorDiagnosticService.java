package com.opspilot.ai.forecast.backtest;

import com.opspilot.ai.analysis.GoldFactorStatus;
import com.opspilot.ai.forecast.ForecastDirection;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 计算单个输入因子对下一交易日黄金方向的独立有效性。 */
@Service
public class FactorDiagnosticService {

    private static final int SCALE = 4;

    private final BacktestService backtests;

    public FactorDiagnosticService(BacktestService backtests) {
        this.backtests = backtests;
    }

    public FactorDiagnosticReport diagnose(UUID id) {
        List<BacktestCase> cases = backtests.results(id, 120);
        return new FactorDiagnosticReport(
                id,
                cases.size(),
                List.of(
                        diagnose("GOLD_MOMENTUM_20", cases, this::momentum),
                        diagnose("REAL_RATE", cases, item -> status(
                                item.snapshot().realRateAssessment().status()
                        )),
                        diagnose("DOLLAR_INDEX", cases, item -> status(
                                item.snapshot().dollarIndexAssessment().status()
                        ))
                )
        );
    }

    private FactorDiagnostic diagnose(
            String factor,
            List<BacktestCase> cases,
            Function<BacktestCase, ForecastDirection> signal
    ) {
        List<ForecastDirection> signals = cases.stream().map(signal).toList();
        int directional = (int) signals.stream()
                .filter(value -> value != ForecastDirection.NEUTRAL)
                .count();
        int hits = 0;
        int directionalHits = 0;
        for (int index = 0; index < cases.size(); index++) {
            ForecastDirection value = signals.get(index);
            if (value == cases.get(index).actualDirection()) {
                hits++;
                if (value != ForecastDirection.NEUTRAL) {
                    directionalHits++;
                }
            }
        }

        return new FactorDiagnostic(
                factor,
                cases.size(),
                directional,
                ratio(directional, cases.size()),
                hits,
                ratio(hits, cases.size()),
                directionalHits,
                ratio(directionalHits, directional),
                new DirectionCounts(
                        count(signals, ForecastDirection.BULLISH),
                        count(signals, ForecastDirection.NEUTRAL),
                        count(signals, ForecastDirection.BEARISH)
                )
        );
    }

    /** 20 期收益率只按方向诊断，不虚构未经验证的数值阈值。 */
    private ForecastDirection momentum(BacktestCase item) {
        int sign = item.snapshot().gold().return20().signum();
        if (sign > 0) return ForecastDirection.BULLISH;
        if (sign < 0) return ForecastDirection.BEARISH;
        return ForecastDirection.NEUTRAL;
    }

    private ForecastDirection status(GoldFactorStatus status) {
        return switch (status) {
            case SUPPORTIVE -> ForecastDirection.BULLISH;
            case PRESSURING -> ForecastDirection.BEARISH;
            case NEUTRAL -> ForecastDirection.NEUTRAL;
        };
    }

    private int count(
            List<ForecastDirection> values,
            ForecastDirection expected
    ) {
        return (int) values.stream().filter(expected::equals).count();
    }

    private BigDecimal ratio(int value, int total) {
        if (total == 0) return null;
        return BigDecimal.valueOf(value).divide(
                BigDecimal.valueOf(total),
                SCALE,
                RoundingMode.HALF_UP
        );
    }
}
