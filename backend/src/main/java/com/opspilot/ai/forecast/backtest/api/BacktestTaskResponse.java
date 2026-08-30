package com.opspilot.ai.forecast.backtest.api;

import com.opspilot.ai.forecast.backtest.BacktestStatus;
import com.opspilot.ai.forecast.backtest.BacktestPriceBasis;
import com.opspilot.ai.forecast.backtest.BacktestSampleSet;
import com.opspilot.ai.forecast.backtest.BacktestTask;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 返回黄金回测任务的配置、进度和状态。 */
public record BacktestTaskResponse(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        int sampleCount,
        String modelName,
        String promptVersion,
        String ruleVersion,
        BacktestPriceBasis priceBasis,
        BacktestSampleSet sampleSet,
        BacktestStatus status,
        int completedCount,
        int hitCount,
        int failedCount,
        String lastError,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
    public static BacktestTaskResponse from(BacktestTask task) {
        return new BacktestTaskResponse(
                task.id(), task.startDate(), task.endDate(), task.sampleCount(),
                task.modelName(), task.promptVersion(), task.ruleVersion(),
                task.priceBasis(), task.sampleSet(),
                task.status(), task.completedCount(), task.hitCount(),
                task.failedCount(), task.lastError(), task.createdAt(),
                task.startedAt(), task.completedAt()
        );
    }
}
