package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;

/** 汇总每日研究准备中的三类数据同步和正式快照保存结果。 */
public record GoldResearchPreparationResult(
        GoldPriceSyncResult goldPriceSync,
        RealRateSyncResult realRateSync,
        DollarIndexSyncResult dollarIndexSync,
        SaveGoldResearchSnapshotResult snapshot
) {
}
