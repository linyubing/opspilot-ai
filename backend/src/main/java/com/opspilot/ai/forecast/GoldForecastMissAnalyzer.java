package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 依据结算事实和生成时快照提供失败诊断线索，不推断模型内部权重或新闻因果。
 *
 * <p>只对已结算且未命中的预测生成原因；未结算或命中的预测返回 {@code null}。
 * 诊断按优先级返回一个观察线索；不能据此自动调权或证明根因。
 * 宏观诊断使用UTC发布日、7自然日观察口径，不等同于生成策略的10日美元放行门槛。
 */
@Component
public class GoldForecastMissAnalyzer {

    private static final int REAL_RATE_MAX_AGE_DAYS = 7;
    private static final int DOLLAR_INDEX_MAX_AGE_DAYS = 7;
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
        if (forecast.status() != ForecastStatus.RESOLVED || forecast.hit() == null || forecast.hit()
                || forecast.predictedDirection() == null || forecast.actualDirection() == null
                || forecast.actualReturn() == null || forecast.predictedDirection() == forecast.actualDirection()) {
            return null;
        }

        boolean opposite = opposite(forecast.predictedDirection(), forecast.actualDirection());

        if (trendConflict(forecast, snapshot)) {
            return reason(
                    "trend_weight_too_high",
                    "短中期趋势冲突（诊断线索）",
                    "快照1日收益=" + snapshot.gold().return1() + "%、20日收益="
                            + snapshot.gold().return20() + "%，两者方向冲突；预测="
                            + forecast.predictedDirection() + "，实际=" + forecast.actualDirection()
                            + "。这些事实不能证明模型权重过高，也不能证明实际变动的原因。",
                    List.of("shortTermMomentumLoss", "midTermTrendStrong")
            );
        }
        if (opposite && largeOppositeMove(forecast)) {
            return reason(
                    "unexpected_market_move",
                    "大幅反向变动（诊断线索）",
                    "实际涨跌幅=" + forecast.actualReturn()
                            + "%，与预测方向相反且绝对值不小于2%。幅度本身不能证明突发新闻或事件原因。",
                    List.of("unexpectedMove")
            );
        }
        if (staleMacroData(forecast, snapshot)) {
            return reason(
                    "stale_macro_data",
                    "宏观观测滞后（诊断线索）",
                    macroEvidence(forecast, snapshot),
                    List.of("staleMacroData")
            );
        }
        if (highVolatility(snapshot)) {
            return reason(
                    "high_volatility",
                    "高波动环境（诊断线索）",
                    "快照20日年化波动率=" + snapshot.gold().volatility20()
                            + "%，不低于20%。这是环境描述，不能证明高波动导致本次误判。",
                    List.of("highVolatility")
            );
        }
        // 三分类失误也包括中性→涨跌、涨跌→中性，不能只解释完全反向的失败。
        return reason(
                    "direction_mismatch",
                    "方向分类未命中",
                    "预测=" + forecast.predictedDirection() + "，实际=" + forecast.actualDirection()
                            + "，实际收益=" + forecast.actualReturn()
                            + "%。分类不一致是已知事实，根因未证明，不能据此自动修改模型。",
                    List.of("directionMismatch")
            );
    }

    private static boolean trendConflict(
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

    private static boolean staleMacroData(StoredGoldDirectionForecast forecast, GoldResearchSnapshot snapshot) {
        if (forecast.createdAt() == null) return false; // 未知发布时刻不猜测当时的数据年龄。
        LocalDate publishedDate = forecast.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        long rateAge = age(publishedDate, snapshot.latestRealRateDate());
        // 美元因子本来允许缺席，缺席不伪装成已过期的观测。
        long dollarAge = snapshot.latestDollarIndexDate() == null ? 0
                : age(publishedDate, snapshot.latestDollarIndexDate());
        return rateAge > REAL_RATE_MAX_AGE_DAYS || dollarAge > DOLLAR_INDEX_MAX_AGE_DAYS;
    }

    private static boolean highVolatility(GoldResearchSnapshot snapshot) {
        BigDecimal volatility20 = snapshot.gold().volatility20();
        return volatility20 != null
                && volatility20.compareTo(HIGH_VOLATILITY_ANNUALIZED) >= 0;
    }

    // 列出实际观测日期和发布时年龄，便于核对；观测滞后不代表已证明误判根因。
    private static String macroEvidence(StoredGoldDirectionForecast forecast, GoldResearchSnapshot snapshot) {
        LocalDate published = forecast.createdAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        return "UTC发布日=" + published
                + "；实际利率观测=" + observation(published, snapshot.latestRealRateDate())
                + "；广义美元指数观测=" + observation(published, snapshot.latestDollarIndexDate())
                + "。宏观观测超过7自然日观察口径。此线索不等同于生成放行门槛违规，"
                + "也不能证明滞后导致本次误判；需用同日期、仅改变该输入的对照实验验证。";
    }

    private static String observation(LocalDate published, LocalDate date) {
        return date == null ? "未提供" : date + "（" + age(published, date) + "天）";
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
