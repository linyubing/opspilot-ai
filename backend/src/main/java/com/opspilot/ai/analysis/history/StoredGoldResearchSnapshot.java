package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.GoldResearchSnapshot;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 表示已正式写入数据库且不可修改的黄金研究历史记录。
 */
public record StoredGoldResearchSnapshot(
        UUID id,
        GoldResearchSnapshot snapshot,
        OffsetDateTime createdAt
) {
}
