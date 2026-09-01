package com.opspilot.ai.analysis.api;

import com.opspilot.ai.analysis.GoldResearchSnapshot;
import com.opspilot.ai.analysis.history.GoldResearchSnapshotRepository;
import com.opspilot.ai.analysis.history.SaveGoldResearchSnapshotResult;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证黄金研究数据新鲜度状态的汇总。 */
class GoldDataStatusServiceTests {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-27T00:00:00Z"),
            ZoneOffset.UTC
    );

    private final FakeSnapshotRepository repository = new FakeSnapshotRepository();
    private final GoldDataStatusService service =
            new GoldDataStatusService(repository, clock);

    @Test
    @DisplayName("全部数据都在7自然日内时为新鲜状态")
    void overallFreshWhenAllItemsCurrent() {
        repository.add(snapshot(
                dates("2026-08-26", "2026-08-25", "2026-08-21")
        ));

        GoldDataStatus status = service.latest();

        assertThat(status.overall()).isEqualTo(DataState.FRESH);
        assertThat(status.items()).allMatch(item -> item.state() == DataState.FRESH);
    }

    @Test
    @DisplayName("任一数据超过7自然日则整体为过期")
    void overallStaleWhenAnyItemStale() {
        repository.add(snapshot(
                dates("2026-08-21", "2026-08-10", "2026-08-21")
        ));

        GoldDataStatus status = service.latest();

        assertThat(status.overall()).isEqualTo(DataState.STALE);
        assertThat(item(status, "gold").state()).isEqualTo(DataState.STALE);
    }

    @Test
    @DisplayName("缺少观测日期时标记为未知")
    void marksUnknownWhenDateMissing() {
        repository.add(new StoredGoldResearchSnapshot(
                UUID.randomUUID(),
                new GoldResearchSnapshot(
                        LocalDate.parse("2026-08-21"),
                        null, LocalDate.parse("2026-08-25"),
                        LocalDate.parse("2026-08-21"),
                        null, null, null, null, null,
                        "v2", "不构成投资建议"
                ),
                OffsetDateTime.parse("2026-08-21T07:30:01Z")
        ));

        assertThat(item(service.latest(), "gold").state())
                .isEqualTo(DataState.UNKNOWN);
    }

    @Test
    @DisplayName("无历史快照时返回空状态")
    void emptyWhenNoSnapshot() {
        GoldDataStatus status = service.latest();
        assertThat(status.items()).isEmpty();
        assertThat(status.overall()).isEqualTo(DataState.UNKNOWN);
    }

    private StoredGoldResearchSnapshot snapshot(LocalDate[] dates) {
        GoldResearchSnapshot inner = new GoldResearchSnapshot(
                dates[0], dates[1], dates[2], dates[3],
                null, null, null, null, null, "v2", "不构成投资建议"
        );
        return new StoredGoldResearchSnapshot(
                UUID.randomUUID(), inner,
                OffsetDateTime.parse("2026-08-21T07:30:01Z")
        );
    }

    private LocalDate[] dates(String analysis, String gold, String rate) {
        return new LocalDate[]{
                LocalDate.parse(analysis), LocalDate.parse(gold),
                LocalDate.parse(rate), LocalDate.parse(rate)
        };
    }

    private GoldDataItemStatus item(GoldDataStatus status, String code) {
        return status.items().stream()
                .filter(i -> i.code().equals(code))
                .findFirst().orElseThrow();
    }

    /** 一个最小假仓储，仅支持阶段测试所需的 findRecent。 */
    private static class FakeSnapshotRepository
            implements GoldResearchSnapshotRepository {
        private final List<StoredGoldResearchSnapshot> snapshots = new ArrayList<>();

        void add(StoredGoldResearchSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public SaveGoldResearchSnapshotResult saveIfAbsent(
                GoldResearchSnapshot snapshot, OffsetDateTime createdAt
        ) {
            return null;
        }

        @Override
        public List<StoredGoldResearchSnapshot> findRecent(int limit) {
            return snapshots.stream()
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<StoredGoldResearchSnapshot> findById(UUID id) {
            return snapshots.stream()
                    .filter(s -> s.id().equals(id))
                    .findFirst();
        }
    }
}
