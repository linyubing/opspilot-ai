package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldResearchPreparationResult;
import com.opspilot.ai.macrodata.api.DollarIndexSyncResponse;
import com.opspilot.ai.macrodata.api.RealRateSyncResponse;
import com.opspilot.ai.marketdata.api.GoldPriceSyncResponse;

/** 对外返回每日研究准备的同步统计和正式快照状态。 */
public record GoldResearchPreparationResponse(
        GoldPriceSyncResponse goldPriceSync,
        RealRateSyncResponse realRateSync,
        DollarIndexSyncResponse dollarIndexSync,
        SaveGoldResearchSnapshotResponse snapshot
) {

    public static GoldResearchPreparationResponse from(
            GoldResearchPreparationResult result
    ) {
        return new GoldResearchPreparationResponse(
                GoldPriceSyncResponse.from(result.goldPriceSync()),
                RealRateSyncResponse.from(result.realRateSync()),
                DollarIndexSyncResponse.from(result.dollarIndexSync()),
                SaveGoldResearchSnapshotResponse.from(result.snapshot())
        );
    }
}
