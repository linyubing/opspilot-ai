package com.opspilot.ai.forecast;

import java.time.LocalDate;

/**
 * 判断黄金有效交易日，用于预测目标日和结算日的计算。
 */
public interface GoldTradingCalendar {
    /**
     * 返回给定日期之后的第一个有效黄金交易日（不含本身）。
     */
    LocalDate nextBusinessDay(LocalDate from);
}
