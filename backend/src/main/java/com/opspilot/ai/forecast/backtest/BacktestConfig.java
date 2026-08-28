package com.opspilot.ai.forecast.backtest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 配置黄金回测专用的单线程后台执行器。 */
@Configuration
public class BacktestConfig {

    @Bean(name = "backtestExecutor")
    public TaskExecutor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("gold-backtest-");
        executor.initialize();
        return executor;
    }
}
