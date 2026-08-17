package com.opspilot.ai.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 黄金每日参考价格。
 *
 * @param symbol 标的代码，第一版固定为 XAUUSD
 * @param priceDate 价格对应日期
 * @param referencePrice 数据源提供的参考价格，不是自行构造的开高低收
 * @param currency 计价币种，数据库统一保存为 usd
 * @param unit 计量单位，数据库统一保存为 troy_ounce（金衡盎司）
 * @param provider 数据供应商
 * @param collectedAt 本系统采集数据的时间
 */
public record MarketPrice(
        String symbol,
        LocalDate priceDate,
        BigDecimal referencePrice,
        String currency,
        String unit,
        String provider,
        OffsetDateTime collectedAt
) {
}
