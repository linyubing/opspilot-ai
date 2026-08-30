package com.opspilot.ai.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 保存黄金当前价格和 1、5、20 期确定性涨跌幅。
 */
public record GoldReturnMetrics(
        BigDecimal currentPrice,
        BigDecimal return1,
        BigDecimal return5,
        BigDecimal return20,
        BigDecimal volatility20,
        OffsetDateTime collectedAt
) {
    /** 兼容不关注波动率的旧测试夹具。 */
    public GoldReturnMetrics(
            BigDecimal currentPrice,
            BigDecimal return1,
            BigDecimal return5,
            BigDecimal return20,
            OffsetDateTime collectedAt
    ) {
        this(currentPrice, return1, return5, return20, null, collectedAt);
    }
}
