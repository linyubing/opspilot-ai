package com.opspilot.ai.analysis.history;

/**
 * 表示快照首次创建或命中已有幂等记录，不表示保存失败。
 */
public record SaveGoldResearchSnapshotResult(
        StoredGoldResearchSnapshot record,
        boolean created
) {
}
