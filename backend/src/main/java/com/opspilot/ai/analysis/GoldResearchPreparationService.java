package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRecordingService;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.DollarIndexSyncService;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncService;
import com.opspilot.ai.marketdata.GoldPriceSyncResult;
import com.opspilot.ai.marketdata.GoldPriceSyncService;
import org.springframework.stereotype.Service;

/** 编排黄金、宏观数据同步和研究快照留痕，不调用大模型。 */
@Service
public class GoldResearchPreparationService {

    private final GoldPriceSyncService goldPriceSyncService;
    private final RealRateSyncService realRateSyncService;
    private final DollarIndexSyncService dollarIndexSyncService;
    private final GoldResearchSnapshotRecordingService recordingService;

    public GoldResearchPreparationService(
            GoldPriceSyncService goldPriceSyncService,
            RealRateSyncService realRateSyncService,
            DollarIndexSyncService dollarIndexSyncService,
            GoldResearchSnapshotRecordingService recordingService
    ) {
        this.goldPriceSyncService = goldPriceSyncService;
        this.realRateSyncService = realRateSyncService;
        this.dollarIndexSyncService = dollarIndexSyncService;
        this.recordingService = recordingService;
    }

    public GoldResearchPreparationResult prepareDaily() {
        /*
         * 外部接口调用不能纳入数据库事务。任一步失败都直接停止，
         * 已保存的数据由各仓储按版本化或幂等规则安全保留。
         */
        GoldPriceSyncResult goldPrice =
                goldPriceSyncService.syncDailyPrices();
        RealRateSyncResult realRate =
                realRateSyncService.syncDailyObservations();
        DollarIndexSyncResult dollarIndex =
                dollarIndexSyncService.syncDailyObservations();
        SaveGoldResearchSnapshotResult snapshot =
                recordingService.recordCurrentSnapshot();

        return new GoldResearchPreparationResult(
                goldPrice,
                realRate,
                dollarIndex,
                snapshot
        );
    }
}
