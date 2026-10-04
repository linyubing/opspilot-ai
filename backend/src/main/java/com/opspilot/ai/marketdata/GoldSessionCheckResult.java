package com.opspilot.ai.marketdata;

import java.time.Instant;
import java.util.List;

/** 候选时段核验结果，不代表历史可得性或模型晋级。 */
public record GoldSessionCheckResult(GoldSession session, Instant checkedAt,
        Status status, int expectedHours, int receivedRows,
        List<Instant> missingHours, String reason) {
    public static final String RULE_VERSION = "sydney-0700-candidate-v1";

    public GoldSessionCheckResult {
        missingHours = List.copyOf(missingHours);
    }

    public enum Status {
        MATCHED, NOT_ENDED, MISSING_HOURS, INVALID_INPUT, OHLC_MISMATCH
    }

    public boolean matched() {
        return status == Status.MATCHED;
    }
}
