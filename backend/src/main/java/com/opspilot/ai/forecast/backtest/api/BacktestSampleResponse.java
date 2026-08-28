package com.opspilot.ai.forecast.backtest.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;

/** 返回冻结回测样本的执行顺序和历史日期。 */
public record BacktestSampleResponse(
        int position,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate asOfDate
) {
}
