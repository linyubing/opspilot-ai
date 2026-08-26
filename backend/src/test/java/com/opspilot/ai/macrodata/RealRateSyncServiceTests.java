package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealRateSyncServiceTests {

    private static final Instant NOW =
            Instant.parse("2026-08-22T01:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("同步后分别统计新增修订未变化和缺失数量")
    void countsEverySaveResult() {
        RealRateBatch batch = new RealRateBatch(
                List.of(
                        observation("2026-08-18", "1.90"),
                        observation("2026-08-19", "1.85"),
                        observation("2026-08-20", "1.82"),
                        observation("2026-08-21", "1.80")
                ),
                5,
                1
        );
        RecordingRepository repository = new RecordingRepository(
                SaveObservationResult.INSERTED,
                SaveObservationResult.INSERTED,
                SaveObservationResult.REVISED,
                SaveObservationResult.UNCHANGED
        );
        RealRateSyncService service = new RealRateSyncService(
                () -> batch,
                repository,
                FIXED_CLOCK
        );

        RealRateSyncResult result = service.syncDailyObservations();

        assertThat(result).isEqualTo(new RealRateSyncResult(
                5,
                1,
                2,
                1,
                1,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)
        ));
        assertThat(repository.collectedTimes)
                .containsOnly(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .hasSize(4);
    }

    @Test
    @DisplayName("批次全部缺失时不写数据库但保留收到和缺失统计")
    void doesNotSaveWhenAllValuesAreMissing() {
        RealRateBatch batch = new RealRateBatch(
                List.of(),
                3,
                3
        );
        RecordingRepository repository =
                new RecordingRepository();
        RealRateSyncService service = new RealRateSyncService(
                () -> batch,
                repository,
                FIXED_CLOCK
        );

        RealRateSyncResult result = service.syncDailyObservations();

        assertThat(result.receivedCount()).isEqualTo(3);
        assertThat(result.missingCount()).isEqualTo(3);
        assertThat(result.insertedCount()).isZero();
        assertThat(result.revisedCount()).isZero();
        assertThat(result.unchangedCount()).isZero();
        assertThat(repository.savedObservations).isEmpty();
    }

    @Test
    @DisplayName("Provider 不可用时原样传播宏观数据异常")
    void propagatesProviderFailure() {
        MacroDataUnavailableException failure =
                new MacroDataUnavailableException(
                        "FRED 实际利率服务暂时不可用"
                );
        RealRateProvider provider = () -> {
            throw failure;
        };
        RealRateSyncService service = new RealRateSyncService(
                provider,
                new RecordingRepository(),
                FIXED_CLOCK
        );

        assertThatThrownBy(service::syncDailyObservations)
                .isSameAs(failure);
    }

    /**
     * 固定数值只验证同步分类，不代表 FRED 的真实实时利率。
     */
    private IncomingMacroObservation observation(
            String date,
            String value
    ) {
        return new IncomingMacroObservation(
                "DFII10",
                LocalDate.parse(date),
                new BigDecimal(value),
                "percent",
                "fred"
        );
    }

    private static class RecordingRepository
            implements MacroObservationRepository {

        private final Queue<SaveObservationResult> results;
        private final List<IncomingMacroObservation> savedObservations =
                new ArrayList<>();
        private final List<OffsetDateTime> collectedTimes =
                new ArrayList<>();

        private RecordingRepository(
                SaveObservationResult... results
        ) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public SaveObservationResult save(
                IncomingMacroObservation observation,
                OffsetDateTime collectedAt
        ) {
            savedObservations.add(observation);
            collectedTimes.add(collectedAt);
            return results.remove();
        }

        @Override
        public Optional<MacroObservation> findLatest(String seriesId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MacroObservation> findRecent(
                String seriesId,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MacroObservation> findLatestAsOf(
                String seriesId,
                OffsetDateTime researchTime
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
