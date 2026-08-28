package com.opspilot.ai.forecast.backtest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 定义黄金回测任务和明细的持久化边界。 */
public interface BacktestRepository {

    BacktestTask create(BacktestTask task);

    Optional<BacktestTask> findTask(UUID id);

    List<BacktestCase> findCases(UUID id, int limit);

    Set<LocalDate> findDoneDates(UUID id);

    boolean start(UUID id, OffsetDateTime time);

    boolean saveCase(BacktestCase item);

    void recordFailure(UUID id, String error);

    void fail(UUID id, String error);

    void complete(UUID id, OffsetDateTime time);
}
