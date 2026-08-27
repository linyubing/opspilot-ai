package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * 按版本化阈值把黄金的真实涨跌幅转换为方向。
 */
@Component
public class GoldForecastRule {

    public static final String RULE_VERSION = "gold-next-session-direction-v1";

    private static final BigDecimal BULLISH_THRESHOLD = new BigDecimal("0.500000");
    private static final BigDecimal BEARISH_THRESHOLD = new BigDecimal("-0.500000");

    /**
     * 根据真实涨跌幅百分比判断黄金方向。
     *
     * @param actualReturn 真实涨跌幅百分比，例如 0.6 表示上涨 0.6%
     * @return 黄金方向
     */
    public ForecastDirection classify(BigDecimal actualReturn) {
        Objects.requireNonNull(actualReturn, "真实涨跌幅不能为空");

        // 边界值正负 0.5% 都归为中性，只有严格越过阈值才判断涨跌。
        if (actualReturn.compareTo(BULLISH_THRESHOLD) > 0) {
            return ForecastDirection.BULLISH;
        }
        if (actualReturn.compareTo(BEARISH_THRESHOLD) < 0) {
            return ForecastDirection.BEARISH;
        }
        return ForecastDirection.NEUTRAL;
    }
}
