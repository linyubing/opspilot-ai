package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 依据预测结算结果和生成时的研究快照，对失败的黄金方向预测做归因。
 *
 * <p>只对已结算且未命中的预测生成原因；未结算或命中的预测返回 {@code null}。
 * 归因规则按优先级依次判定，命中即返回该主原因。数据年龄当前使用自然日近似
 * （实际利率最多滞后 7 天、美元指数最多滞后 5 个交易日），后续阶段将统一交易日口径。
 */
@Component
public class GoldForecastMissAnalyzer {

    private static final int REAL_RATE_MAX_AGE_DAYS = 7;
    private static final int DOLLAR_INDEX_MAX_AGE_DAYS = 5;
    private static final BigDecimal OPPOSITE_MOVE_PERCENT_THRESHOLD = new BigDecimal("2");
    private static final BigDecimal HIGH_VOLATILITY_ANNUALIZED = new BigDecimal("20");

    /**
     * 对一条预测做失败归因；非失败预测返回 {@code null}。
     */
    public GoldForecastMissReason analyze(
            StoredGoldDirectionForecast forecast,
            GoldResearchSnapshot snapshot
    ) {
        if (forecast == null || snapshot == null) {
            return null;
        }
        if (forecast.hit() == null || forecast.hit()) {
            return null;
        }

        boolean opposite = opposite(forecast.predictedDirection(), forecast.actualDirection());

        if (PredictionsReverseTrends(forecast, snapshot)) {
            return reason(
                    "trend_weight_too_high",
                    "短中期趋势权重过高",
                    "预测上涨但黄金短期动量转弱、中期趋势仍强，模型过度依赖中期上涨权重。",
                    List.of("shortTermMomentumLoss", "midTermTrendStrong")
            );
        }
        if (opposite && largeOppositeMove(forecast)) {
            return reason(
                    "unexpected_market_move",
                    "突发市场波动",
                    "实际涨跌幅达 " + forecast.actualReturn() + "%，属于超出常规范围的突发波动。",
                    List.of("unexpectedMove")
            );
        }
        if (staleMacroData(snapshot)) {
            return reason(
                    "stale_macro_data",
                    "宏观数据过期",
                    "预测形成时实际利率或美元指数数据已超过允许的时效，宏观变量置信度下降。",
                    List.of("staleMacroData")
            );
        }
        if (highVolatility(snapshot)) {
            return reason(
                    "high_volatility",
                    "高波动环境",
                    "预测形成时黄金处于高波动区间，方向判断不确定性偏高。",
                    List.of("highVolatility")
            );
        }
        if (opposite) {
            return reason(
                    "direction_mismatch",
                    "方向判断失误",
                    "预测方向与实际结算方向相反，属于常规方向性失误。",
                    List.of("directionMismatch")
            );
        }
        return null;
    }

    private static boolean PredictionsReverseTrends(
            StoredGoldDirectionForecast forecast,
            GoldResearchSnapshot snapshot
    ) {
        boolean bullishThenBearish =
                forecast.predictedDirection() == ForecastDirection.BULLISH
                        && forecast.actualDirection() == ForecastDirection.BEARISH;
        boolean bearishThenBullish =
                forecast.predictedDirection() == ForecastDirection.BEARISH
                        && forecast.actualDirection() == ForecastDirection.BULLISH;
        if (!bullishThenBearish && !bearishThenBullish) {
            return false;
        }
        BigDecimal return1 = snapshot.gold().return1();
        BigDecimal return20 = snapshot.gold().return20();
        if (bullishThenBearish) {
            return return1 != null && return20 != null
                    && return1.signum() < 0 && return20.signum() > 0;
        }
        return return1 != null && return20 != null
                && return1.signum() > 0 && return20.signum() < 0;
    }

    private static boolean opposite(
            ForecastDirection predicted, ForecastDirection actual
    ) {
        if (predicted == null || actual == null) {
            return false;
        }
        return (predicted == ForecastDirection.BULLISH && actual == ForecastDirection.BEARISH)
                || (predicted == ForecastDirection.BEARISH && actual == ForecastDirection.BULLISH);
    }

    private static boolean largeOppositeMove(StoredGoldDirectionForecast forecast) {
        if (forecast.actualReturn() == null) {
            return false;
        }
        return forecast.actualReturn().abs()
                .compareTo(OPPOSITE_MOVE_PERCENT_THRESHOLD) >= 0;
    }

    private static boolean staleMacroData(GoldResearchSnapshot snapshot) {
        long rateAge = age(snapshot.analysisDate(), snapshot.latestRealRateDate());
        long dollarAge = age(snapshot.analysisDate(), snapshot.latestDollarIndexDate());
        return rateAge > REAL_RATE_MAX_AGE_DAYS || dollarAge > DOLLAR_INDEX_MAX_AGE_DAYS;
    }

    private static boolean highVolatility(GoldResearchSnapshot snapshot) {
        BigDecimal volatility20 = snapshot.gold().volatility20();
        return volatility20 != null
                && volatility20.compareTo(HIGH_VOLATILITY_ANNUALIZED) >= 0;
    }

    private static long age(LocalDate from, LocalDate observation) {
        if (from == null || observation == null) {
            return Long.MAX_VALUE;
        }
        return ChronoUnit.DAYS.between(observation, from);
    }

    private static GoldForecastMissReason reason(
            String code, String title, String detail, List<String> tags
    ) {
        return new GoldForecastMissReason(code, title, detail, List.copyOf(tags));
    }
}
