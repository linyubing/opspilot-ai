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
    /** 兼容改造前的调用；新建回测必须显式填写价格口径。 */
    public BacktestTask(
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
        this(
                id, startDate, endDate, sampleCount, modelName,
                promptVersion, ruleVersion,
                BacktestPriceBasis.LEGACY_REFERENCE,
                BacktestSampleSet.DEFAULT, status,
                completedCount, hitCount, failedCount, lastError,
                createdAt, startedAt, completedAt
        );
    }

    /** 兼容只记录价格口径、尚未记录样本集合的调用。 */
    public BacktestTask(
            UUID id,
            LocalDate startDate,
            LocalDate endDate,
            int sampleCount,
            String modelName,
            String promptVersion,
            String ruleVersion,
            BacktestPriceBasis priceBasis,
            BacktestStatus status,
            int completedCount,
            int hitCount,
            int failedCount,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt
    ) {
        this(
                id, startDate, endDate, sampleCount, modelName,
                promptVersion, ruleVersion, priceBasis,
                BacktestSampleSet.DEFAULT, status,
                completedCount, hitCount, failedCount, lastError,
                createdAt, startedAt, completedAt
        );
    }
}
