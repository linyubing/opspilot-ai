package com.opspilot.ai.forecast.backtest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 负责取得回测运行权并提交单线程后台任务。 */
@Service
public class BacktestJobService {

    private final BacktestRepository repo;
    private final BacktestService service;
    private final BacktestRunner runner;
    private final TaskExecutor executor;
    private final Clock clock;

    public BacktestJobService(
            BacktestRepository repo,
            BacktestService service,
            BacktestRunner runner,
            @Qualifier("backtestExecutor") TaskExecutor executor,
            Clock clock
    ) {
        this.repo = repo;
        this.service = service;
        this.runner = runner;
        this.executor = executor;
        this.clock = clock;
    }

    public BacktestTask start(UUID id) {
        OffsetDateTime now = OffsetDateTime.ofInstant(
                clock.instant(),
                ZoneOffset.UTC
        );
        if (repo.start(id, now)) {
            executor.execute(() -> runner.run(id));
        }
        return service.get(id);
    }
}
