package com.opspilot.ai.analysis.narrative;

import java.util.UUID;

/** 表示请求解读的正式黄金研究快照不存在。 */
public class GoldResearchSnapshotNotFoundException extends RuntimeException {

    public GoldResearchSnapshotNotFoundException(UUID snapshotId) {
        super("未找到黄金研究快照：" + snapshotId);
    }
}
