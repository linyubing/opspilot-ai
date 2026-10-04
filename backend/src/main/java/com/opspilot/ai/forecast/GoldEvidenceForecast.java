package com.opspilot.ai.forecast;

import java.math.BigDecimal;
import java.util.List;

/** 研究候选的预测与可核对事实引用；不代表已认证的准确率。 */
public record GoldEvidenceForecast(GoldDirectionForecastContent forecast, List<Fact> evidence) {
    /** key对应快照字段；value是原值，trend是该变化值的符号而非未来方向。 */
    public record Fact(String key, BigDecimal value, Trend trend) { }

    /** 变化值为正、负、零或缺失；不描述当前价格的高低。 */
    public enum Trend { UP, DOWN, FLAT, MISSING }
}
