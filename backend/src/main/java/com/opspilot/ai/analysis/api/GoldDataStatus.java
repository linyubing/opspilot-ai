package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 汇总黄金研究快照各输入数据项的新鲜度及整体状态。
 *
 * <p>新鲜度规则与现有美元指数一致：观测日期距当前 UTC 日期不超过 7 个自然日
 * 视为新鲜，超过则视为过期。缺少日期或无法判断标记为未知。
 */
public record GoldDataStatus(
        List<GoldDataItemStatus> items,
        DataState overall,
        LocalDate analysisDate,
        OffsetDateTime generatedAt
) {

    private static final long MAX_AGE_DAYS = 7;

    public static GoldDataStatus from(
            StoredGoldResearchSnapshot stored,
            Clock clock
    ) {
        GoldResearchSnapshot snapshot = stored.snapshot();
        List<GoldDataItemStatus> items = List.of(
                item("gold", "黄金价格", snapshot.latestGoldDate(), clock),
                item("realRate", "实际利率", snapshot.latestRealRateDate(), clock),
                item("dollarIndex", "美元指数", snapshot.latestDollarIndexDate(), clock)
        );
        DataState overall = items.stream()
                .allMatch(i -> i.state() == DataState.FRESH) ? DataState.FRESH
                : items.stream().allMatch(i -> i.state() == DataState.UNKNOWN)
                ? DataState.UNKNOWN : DataState.STALE;
        return new GoldDataStatus(
                items,
                overall,
                snapshot.analysisDate(),
                OffsetDateTime.now(clock)
        );
    }

    public static GoldDataStatus empty() {
        return new GoldDataStatus(List.of(), DataState.UNKNOWN, null, null);
    }

    private static GoldDataItemStatus item(
            String code, String name, LocalDate observationDate, Clock clock
    ) {
        if (observationDate == null) {
            return new GoldDataItemStatus(
                    code, name, null, DataState.UNKNOWN, "缺少观测日期"
            );
        }
        long age = ChronoUnit.DAYS.between(observationDate, LocalDate.now(clock));
        if (age < 0 || age > MAX_AGE_DAYS) {
            return new GoldDataItemStatus(
                    code, name, observationDate, DataState.STALE,
                    "观测日期距当前已 " + Math.max(age, 0) + " 天，超过允许的 "
                            + MAX_AGE_DAYS + " 个自然日"
            );
        }
        return new GoldDataItemStatus(
                code, name, observationDate, DataState.FRESH,
                "观测日期距当前 " + age + " 天，处于允许时效内"
        );
    }
}
