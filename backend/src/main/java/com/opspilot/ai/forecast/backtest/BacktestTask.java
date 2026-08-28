package com.opspilot.ai.forecast.backtest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 保存一次黄金历史回测任务及其进度。 */
public record BacktestTask(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        int sampleCount,
        String modelName,
        String promptVersion,
        String ruleVersion,
        BacktestStatus status,
        int completedCount,
        int hitCount,
        int failedCount,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
