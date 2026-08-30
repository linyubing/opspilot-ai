package com.opspilot.ai.forecast;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** 校验生成黄金方向预测时三类输入数据是否仍在允许时效内。 */
@Component
public class GoldForecastDataFreshnessPolicy {

    private static final int GOLD_MAX_AGE_DAYS = 3;
    private static final int REAL_RATE_MAX_AGE_DAYS = 7;
    private static final int DOLLAR_INDEX_MAX_AGE_DAYS = 10;

    private final Clock clock;

    public GoldForecastDataFreshnessPolicy(Clock clock) {
        this.clock = clock;
    }

    public void validate(GoldResearchSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "黄金研究快照不能为空");
        LocalDate currentDate = LocalDate.now(clock);

        validateDate(
                "黄金价格",
                snapshot.latestGoldDate(),
                currentDate,
                GOLD_MAX_AGE_DAYS
        );
        validateDate(
                "实际利率",
                snapshot.latestRealRateDate(),
                currentDate,
                REAL_RATE_MAX_AGE_DAYS
        );
        validateDate(
                "美元指数",
                snapshot.latestDollarIndexDate(),
                currentDate,
                DOLLAR_INDEX_MAX_AGE_DAYS
        );
    }

    private void validateDate(
            String dataName,
            LocalDate observationDate,
            LocalDate currentDate,
            int maxAgeDays
    ) {
        long ageDays = ChronoUnit.DAYS.between(
                Objects.requireNonNull(observationDate, dataName + "日期不能为空"),
                currentDate
        );

        if (ageDays < 0) {
            throw new StaleGoldForecastDataException(
                    dataName + "日期来自未来：" + observationDate
            );
        }
        if (ageDays > maxAgeDays) {
            throw new StaleGoldForecastDataException(
                    dataName + "已过期：观测日期=" + observationDate
                            + "，当前日期=" + currentDate
                            + "，允许最大天数=" + maxAgeDays
            );
        }
    }
}
