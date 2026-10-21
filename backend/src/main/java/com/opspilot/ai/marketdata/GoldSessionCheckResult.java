package com.opspilot.ai.marketdata;

import java.time.Instant;
import java.util.List;

/** 候选时段核验结果，不代表历史可得性或模型晋级。 */
public record GoldSessionCheckResult(GoldSession session, Instant checkedAt,
        Status status, int expectedHours, int receivedRows,
        List<Instant> missingHours, String reason, Input input) {
    public static final String RULE_VERSION = "sydney-0700-candidate-v1";

    public GoldSessionCheckResult {
        missingHours = List.copyOf(missingHours);
    }

    /** 兼容纯计算和历史调用；没有输入回执时不能假装可复验。 */
    public GoldSessionCheckResult(GoldSession session, Instant checkedAt, Status status,
            int expectedHours, int receivedRows, List<Instant> missingHours, String reason) {
        this(session, checkedAt, status, expectedHours, receivedRows, missingHours, reason, null);
    }

    /** 精确保存用于比较的原价和小时输入；请求UTC与响应确认UTC必须区分。 */
    public record Input(GoldDailyBar day, List<GoldHourBar> hours, String timezoneBasis) {
        public Input { hours = List.copyOf(hours); }
    }

    public enum Status {
        MATCHED, NOT_ENDED, MISSING_HOURS, INVALID_INPUT, OHLC_MISMATCH
    }

    public boolean matched() {
        return status == Status.MATCHED;
    }
}
