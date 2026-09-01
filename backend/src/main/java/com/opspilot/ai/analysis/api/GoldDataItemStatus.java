package com.opspilot.ai.analysis.api;

import java.time.LocalDate;

/** 描述黄金研究输入中单个数据项的新鲜度状态。 */
public record GoldDataItemStatus(
        String code,
        String name,
        LocalDate observationDate,
        DataState state,
        String detail
) {
}
