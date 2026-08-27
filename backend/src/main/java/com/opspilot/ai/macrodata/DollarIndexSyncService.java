package com.opspilot.ai.macrodata;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

/** 负责获取并按版本语义保存广义美元指数观测。 */
@Service
public class DollarIndexSyncService {

    private final DollarIndexProvider provider;
    private final MacroObservationRepository repository;
    private final Clock clock;

    public DollarIndexSyncService(
            DollarIndexProvider provider,
            MacroObservationRepository repository,
            Clock clock
    ) {
        this.provider = provider;
        this.repository = repository;
        this.clock = clock;
    }

    public DollarIndexSyncResult syncDailyObservations() {
        DollarIndexBatch batch = provider.fetchDailyObservations();
        // 同一批数据共享采集时间，避免循环保存时产生时间漂移。
        OffsetDateTime collectedAt = OffsetDateTime.now(clock);

        int insertedCount = 0;
        int revisedCount = 0;
        int unchangedCount = 0;
        for (IncomingMacroObservation observation : batch.observations()) {
            SaveObservationResult result = repository.save(
                    observation,
                    collectedAt
            );
            switch (result) {
                case INSERTED -> insertedCount++;
                case REVISED -> revisedCount++;
                case UNCHANGED -> unchangedCount++;
            }
        }

        return new DollarIndexSyncResult(
                batch.receivedCount(),
                batch.missingCount(),
                insertedCount,
                revisedCount,
                unchangedCount,
                collectedAt
        );
    }
}
