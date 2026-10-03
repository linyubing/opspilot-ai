package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 编排当前研究快照生成与不可变历史留痕，不负责指标计算和 SQL。
 */
@Service
public class GoldResearchSnapshotRecordingService {

    private final GoldResearchSnapshotService snapshotService;
    private final GoldResearchSnapshotRepository repository;
    private final Clock clock;

    public GoldResearchSnapshotRecordingService(
            GoldResearchSnapshotService snapshotService,
            GoldResearchSnapshotRepository repository,
            Clock clock
    ) {
        this.snapshotService = snapshotService;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public SaveGoldResearchSnapshotResult recordCurrentSnapshot() {
        GoldResearchSnapshot snapshot =
                snapshotService.createSnapshot();

        return repository.saveIfAbsent(
                snapshot,
                // 自行采样的时刻统一到微秒，避免数据库舍入后早于核验时刻。
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        );
    }
}
