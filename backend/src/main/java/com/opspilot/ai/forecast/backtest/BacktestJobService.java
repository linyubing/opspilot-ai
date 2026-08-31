package com.opspilot.ai.forecast.backtest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 负责取得回测运行权并提交单线程后台任务。 */
@Service
public class BacktestJobService {

    private final BacktestRepository repo;
    private final BacktestService service;
    private final BacktestRunner runner;
    private final TaskExecutor executor;
    private final Clock clock;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

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
            submit(id);
        }
        return service.get(id);
    }

    /**
     * 继续执行应用重启前已经处于运行状态的任务。
     * 已完成日期由运行器跳过，当前进程中的重复请求也不会重复提交。
     */
    public BacktestTask resume(UUID id) {
        BacktestTask task = service.get(id);
        if (task.status() != BacktestStatus.RUNNING) {
            throw new InvalidBacktestRequestException(
                    "只有运行中的回测任务可以恢复"
            );
        }
        submit(id);
        return service.get(id);
    }

    private void submit(UUID id) {
        if (!active.add(id)) {
            return;
        }
        executor.execute(() -> {
            try {
                runner.run(id);
            } finally {
                active.remove(id);
            }
        });
    }
}
