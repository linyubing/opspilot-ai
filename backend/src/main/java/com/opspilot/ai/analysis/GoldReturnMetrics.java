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
        OffsetDateTime collectedAt
) {
}
