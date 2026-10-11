package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.GoldResearchSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transaction;

    public GoldResearchSnapshotRecordingService(
            GoldResearchSnapshotService snapshotService,
            GoldResearchSnapshotRepository repository,
            Clock clock
    ) {
        this(snapshotService, repository, clock, null);
    }

    @Autowired
    public GoldResearchSnapshotRecordingService(GoldResearchSnapshotService snapshotService,
            GoldResearchSnapshotRepository repository, Clock clock, PlatformTransactionManager manager) {
        this.snapshotService = snapshotService;
        this.repository = repository;
        this.clock = clock;
        this.transaction = manager == null ? null : new TransactionTemplate(manager);
        if (transaction != null) {
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setTimeout(15);
        }
    }

    // 一次冻结使用同一数据库读视图，避免同步中途混用输入版本。
    public SaveGoldResearchSnapshotResult recordCurrentSnapshot() {
        for (int attempt = 0; ; attempt++) {
            try {
                return transaction == null ? freeze()
                        : transaction.execute(status -> freeze());
            } catch (ConcurrencyFailureException conflict) {
                // RR冲突必须重开整个事务和读视图，不能在旧事务内重试SQL。
                if (attempt >= 2) throw conflict;
            }
        }
    }

    private SaveGoldResearchSnapshotResult freeze() {
        GoldResearchSnapshot snapshot =
                snapshotService.createSnapshot();

        return repository.saveIfAbsent(
                snapshot,
                // 自行采样的时刻统一到微秒，避免数据库舍入后早于核验时刻。
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
        );
    }
}
