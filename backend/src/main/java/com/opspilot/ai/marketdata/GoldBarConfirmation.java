package com.opspilot.ai.marketdata;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 保存供应商已结束交易日边界及本次报价的规范化指纹。 */
public record GoldBarConfirmation(
        String source, LocalDate closedDay, OffsetDateTime checkedAt, String receiptHash
) {
    public static final String SOURCE = "twelve_data_quote_eod_v1";
}
