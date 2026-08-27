package com.opspilot.ai.macrodata;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 根据 UTC 当前日期判断广义美元指数观测是否新鲜。 */
@Component
public class DollarIndexFreshnessEvaluator {

    private final Clock clock;

    public DollarIndexFreshnessEvaluator(Clock clock) {
        this.clock = clock;
    }

    public DollarIndexFreshness evaluate(LocalDate observationDate) {
        // 计算观测日期距离 UTC 当前日期的自然日数量。
        long ageDays = ChronoUnit.DAYS.between(
                observationDate,
                LocalDate.now(clock)
        );

        // 负数说明观测日期来自未来，属于异常数据。
        if (ageDays < 0) {
            throw new IllegalArgumentException("观测日期不能晚于当前日期");
        }

        // 七天以内包含第七天，仍然视为当前可用数据。
        if (ageDays <= 7) {
            return DollarIndexFreshness.CURRENT;
        }

        return DollarIndexFreshness.STALE;
    }
}
