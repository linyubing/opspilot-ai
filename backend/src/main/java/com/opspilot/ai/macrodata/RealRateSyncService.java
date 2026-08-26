package com.opspilot.ai.macrodata;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class RealRateSyncService {

    private final RealRateProvider provider;
    private final MacroObservationRepository repository;
    private final Clock clock;

    public RealRateSyncService(
            RealRateProvider provider,
            MacroObservationRepository repository,
            Clock clock
    ) {
        this.provider = provider;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 从 FRED 获取每日实际利率，并保存每条观测数据。
     */
    public RealRateSyncResult syncDailyObservations() {
        RealRateBatch batch = provider.fetchDailyObservations();

        // 同一批数据共用采集时间，避免循环过程中产生不同时间。
        OffsetDateTime collectedAt = OffsetDateTime.now(clock);

        int insertedCount = 0;
        int revisedCount = 0;
        int unchangedCount = 0;

        for (IncomingMacroObservation observation : batch.observations()) {
            SaveObservationResult saveResult = repository.save(observation, collectedAt);

            // 根据仓储返回的保存结果进行分类统计。
            switch (saveResult) {
                case INSERTED -> insertedCount++;
                case REVISED -> revisedCount++;
                case UNCHANGED -> unchangedCount++;
            }
        }

        return new RealRateSyncResult(
                batch.receivedCount(),
                batch.missingCount(),
                insertedCount,
                revisedCount,
                unchangedCount,
                collectedAt
        );
    }
}
