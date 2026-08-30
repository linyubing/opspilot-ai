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
        return diagnose(id, cases, BacktestCase::actualDirection);
    }

    /** 允许周期诊断使用不同持有期的真实方向复用同一套因子统计。 */
    FactorDiagnosticReport diagnose(
            UUID id,
            List<BacktestCase> cases,
            Function<BacktestCase, ForecastDirection> actual
    ) {
        List<FactorSample> samples = cases.stream()
                .map(item -> new FactorSample(
                        item.snapshot(), actual.apply(item)
                ))
                .toList();
        return diagnose(id, samples);
    }

    FactorDiagnosticReport diagnose(UUID id, List<FactorSample> samples) {
        return new FactorDiagnosticReport(
                id,
                samples.size(),
                List.of(
                        diagnose(
                                "GOLD_MOMENTUM_20", samples,
                                this::momentum
                        ),
                        diagnose(
                                "REAL_RATE", samples,
                                item -> status(item.snapshot()
                                        .realRateAssessment().status())
                        ),
                        diagnose(
                                "DOLLAR_INDEX", samples,
                                item -> status(item.snapshot()
                                        .dollarIndexAssessment().status())
                        ),
                        diagnose(
                                "SHORT_TERM_REVERSAL", samples,
                                this::shortReversal
                        )
                )
        );
    }

    /** 根据 1 日与 5 日收益率是否同向，生成未经调参的短期反转信号。 */
    ForecastDirection shortReversal(FactorSample item) {
        int daily = item.snapshot().gold().return1().signum();
        int weekly = item.snapshot().gold().return5().signum();

        // 短期连续上涨，判断接下来可能回落。
        if (daily > 0 && weekly > 0) {
            return ForecastDirection.BEARISH;
        }

        // 短期连续下跌，判断接下来可能反弹。
        if (daily < 0 && weekly < 0) {
            return ForecastDirection.BULLISH;
        }

        // 两个周期方向冲突时，不强行预测。
        return ForecastDirection.NEUTRAL;
    }

    private FactorDiagnostic diagnose(
            String factor,
            List<FactorSample> samples,
            Function<FactorSample, ForecastDirection> signal
    ) {
        List<ForecastDirection> signals = samples.stream().map(signal).toList();
        int directional = (int) signals.stream()
                .filter(value -> value != ForecastDirection.NEUTRAL)
                .count();
        int hits = 0;
        int directionalHits = 0;
        for (int index = 0; index < samples.size(); index++) {
            ForecastDirection value = signals.get(index);
            if (value == samples.get(index).actual()) {
                hits++;
                if (value != ForecastDirection.NEUTRAL) {
                    directionalHits++;
                }
            }
        }

        return new FactorDiagnostic(
                factor,
                samples.size(),
                directional,
                ratio(directional, samples.size()),
                hits,
                ratio(hits, samples.size()),
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
    private ForecastDirection momentum(FactorSample item) {
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
