package com.opspilot.ai.analysis;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRecordingService;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.macrodata.DollarIndexSyncResult;
import com.opspilot.ai.macrodata.DollarIndexSyncService;
import com.opspilot.ai.macrodata.RealRateSyncResult;
import com.opspilot.ai.macrodata.RealRateSyncService;
import com.opspilot.ai.marketdata.GoldDailyBarSyncResult;
import com.opspilot.ai.marketdata.GoldDailyBarSyncService;
import org.springframework.stereotype.Service;

/** 编排黄金、宏观数据同步和研究快照留痕，不调用大模型。 */
@Service
public class GoldResearchPreparationService {

    private final GoldDailyBarSyncService goldBarSync;
    private final RealRateSyncService realRateSyncService;
    private final DollarIndexSyncService dollarIndexSyncService;
    private final GoldResearchSnapshotRecordingService recordingService;

    public GoldResearchPreparationService(
            GoldDailyBarSyncService goldBarSync,
            RealRateSyncService realRateSyncService,
            DollarIndexSyncService dollarIndexSyncService,
            GoldResearchSnapshotRecordingService recordingService
    ) {
        this.goldBarSync = goldBarSync;
        this.realRateSyncService = realRateSyncService;
        this.dollarIndexSyncService = dollarIndexSyncService;
        this.recordingService = recordingService;
    }

    public GoldResearchPreparationResult prepareDaily() {
        /*
         * 外部接口调用不能纳入数据库事务。任一步失败都直接停止，
         * 已保存的数据由各仓储按版本化或幂等规则安全保留。
         */
        // 快照计算用OHLC，必须同步同一份输入，不能用旧参考价同步替代。
        GoldDailyBarSyncResult goldBars = goldBarSync.sync();
        RealRateSyncResult realRate =
                realRateSyncService.syncDailyObservations();
        DollarIndexSyncResult dollarIndex =
                dollarIndexSyncService.syncDailyObservations();
        SaveGoldResearchSnapshotResult snapshot =
                recordingService.recordCurrentSnapshot();

        return new GoldResearchPreparationResult(
                goldBars,
                realRate,
                dollarIndex,
                snapshot
        );
    }
}
