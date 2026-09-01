package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 基于最新已保存的研究快照汇总输入数据的健康状态。
 */
@Service
public class GoldDataStatusService {

    private final GoldResearchSnapshotRepository snapshotRepository;
    private final Clock clock;

    public GoldDataStatusService(
            GoldResearchSnapshotRepository snapshotRepository,
            Clock clock
    ) {
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    public GoldDataStatus latest() {
        return snapshotRepository.findRecent(1).stream()
                .findFirst()
                .map(snapshot -> GoldDataStatus.from(snapshot, clock))
                .orElseGet(GoldDataStatus::empty);
    }
}
