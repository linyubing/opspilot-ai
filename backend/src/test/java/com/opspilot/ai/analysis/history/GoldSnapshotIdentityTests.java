package com.opspilot.ai.analysis.history;

import com.opspilot.ai.analysis.*;
import com.opspilot.ai.macrodata.MacroObservation;
import com.opspilot.ai.macrodata.MacroObservationRepository;
import com.opspilot.ai.marketdata.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 合成输入只验证不可变留痕，不能作为真实行情或准确率样本。 */
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class GoldSnapshotIdentityTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-11T03:00:00Z");
    @Autowired GoldResearchSnapshotRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final List<UUID> ids = new ArrayList<>();
    private final LocalDate date = LocalDate.of(1980, 1, 1).plusDays(new Random().nextInt(9000));

    @AfterEach
    void clean() {
        for (UUID id : ids) jdbc.update("delete from gold_research_snapshot where id = ?", id);
    }

    @Test
    @DisplayName("同一黄金日的宏观输入更新应新增快照，旧快照全文不变")
    void keepsNewInputs() {
        var first = save(snapshot(date.minusDays(2), "2.00"));
        String oldJson = jdbc.queryForObject("select row_to_json(s)::text from gold_research_snapshot s where id = ?",
                String.class, first.record().id());
        var second = save(snapshot(date.minusDays(1), "2.10"));
        assertThat(second.created()).isTrue();
        assertThat(second.record().id()).isNotEqualTo(first.record().id());
        assertThat(second.record().snapshot().latestRealRateDate()).isEqualTo(date.minusDays(1));
        assertThat(jdbc.queryForObject("select row_to_json(s)::text from gold_research_snapshot s where id = ?",
                String.class, first.record().id())).isEqualTo(oldJson);
    }

    @Test
    @DisplayName("重复冻结相同事实不新增样本，也不改第一次留痕")
    void keepsSameInputs() {
        var first = save(snapshot(date.minusDays(2), "2.00"));
        var second = save(snapshot(date.minusDays(2), "2.0000"));
        assertThat(second.created()).isFalse();
        assertThat(second.record().id()).isEqualTo(first.record().id());
    }

    @Test
    @DisplayName("旧黄金窗口记录不补造宏观依据；首次完整身份另留一条记录")
    void keepsLegacyInput() {
        var current = snapshot(date.minusDays(2), "2.00");
        var legacy = copy(current, new GoldSnapshotInput(current.input().bars(), current.input().checkedAt()));
        var old = save(legacy);
        String oldJson = jdbc.queryForObject("select row_to_json(s)::text from gold_research_snapshot s where id = ?",
                String.class, old.record().id());
        var complete = save(current);
        assertThat(complete.created()).isTrue();
        assertThat(complete.record().id()).isNotEqualTo(old.record().id());
        assertThat(jdbc.queryForObject("select row_to_json(s)::text from gold_research_snapshot s where id = ?",
                String.class, old.record().id())).isEqualTo(oldJson);
        assertThat(jdbc.queryForObject("select input_hash from gold_research_snapshot where id = ?",
                String.class, old.record().id())).isNull();
        assertThat(jdbc.queryForObject("select input_hash from gold_research_snapshot where id = ?",
                String.class, complete.record().id())).matches("[0-9a-f]{64}");
        assertThat(complete.record().snapshot().input().macro().rates()).hasSize(4);
    }

    @Test
    @DisplayName("只变核验/采集时间、确认锚点和编号，不改变事实身份")
    void ignoresReceiptTimes() {
        var first = snapshot(date.minusDays(2), "2.00");
        var input = first.input();
        var rates = input.macro().rates().stream().map(row -> new MacroObservation(UUID.randomUUID(),
                row.seriesId(), row.observationDate(), row.value(), row.unit(), row.provider(),
                NOW.minusMinutes(30), null)).toList();
        var rate = first.realRate();
        var changed = new GoldResearchSnapshot(first.analysisDate(), first.latestGoldDate(), first.latestRealRateDate(),
                first.latestDollarIndexDate(), first.gold(), new RealRateChangeMetrics(rate.currentRate(),
                rate.percentagePointChange1(), rate.percentagePointChange5(), rate.percentagePointChange20(),
                rate.basisPointChange1(), rate.basisPointChange5(), rate.basisPointChange20(), NOW.minusMinutes(30)),
                first.dollarIndex(), first.realRateAssessment(), first.dollarIndexAssessment(), first.researchVersion(),
                first.disclaimer(), new GoldSnapshotInput(input.bars().stream().map(bar -> new GoldDailyBar(
                bar.symbol(), bar.priceDate(), bar.open(), bar.high(), bar.low(), bar.close(), bar.currency(), bar.unit(),
                bar.provider(), bar.collectedAt(), new GoldBarConfirmation(GoldBarConfirmation.SOURCE,
                date.plusDays(1), NOW, "b".repeat(64)))).toList(), NOW,
                new GoldMacroInput(rates, input.macro().dollars())));
        assertThat(save(changed).record().id()).isEqualTo(save(first).record().id());
    }

    @Test
    @DisplayName("宏观采集晚于冻结时刻必须拒绝，不新增未来输入快照")
    void rejectsFutureMacro() {
        var first = snapshot(date.minusDays(2), "2.00");
        var rates = first.input().macro().rates().stream().map(row -> new MacroObservation(row.id(),
                row.seriesId(), row.observationDate(), row.value(), row.unit(), row.provider(), NOW.plusSeconds(1), null)).toList();
        var changed = copy(first, new GoldSnapshotInput(first.input().bars(), NOW,
                new GoldMacroInput(rates, first.input().macro().dollars())));
        assertThatThrownBy(() -> save(changed)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("宏观原始输入");
    }

    @Test
    @DisplayName("摘要相同但参与计算的历史宏观日期不同，必须有不同输入身份")
    void distinguishesRawDates() {
        var original = snapshot(date.minusDays(2), "2.00");
        var rates = new ArrayList<>(original.input().macro().rates());
        var row = rates.get(2);
        rates.set(2, new MacroObservation(row.id(), row.seriesId(), row.observationDate().minusDays(1),
                row.value(), row.unit(), row.provider(), row.collectedAt(), row.supersededAt()));
        var changed = copy(original, new GoldSnapshotInput(original.input().bars(), NOW,
                new GoldMacroInput(rates, original.input().macro().dollars())));
        assertThat(save(changed).record().id()).isNotEqualTo(save(original).record().id());
    }

    @Test
    @DisplayName("宏观当前值超过数据库精度必须拒绝，不能舍入后使原始输入与摘要失配")
    void rejectsRoundedMacro() {
        var value = snapshot(date.minusDays(2), "2.1234567");
        assertThatThrownBy(() -> save(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("精度");
    }

    @Test
    @DisplayName("并发冻结相同事实仅新增一行，完整键回读不串快照")
    void concurrentSameInput() throws Exception {
        var value = snapshot(date.minusDays(2), "2.00");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs = pool.invokeAll(List.<java.util.concurrent.Callable<SaveGoldResearchSnapshotResult>>of(() -> repository.saveIfAbsent(value, NOW),
                    () -> repository.saveIfAbsent(value, NOW)));
            var first = jobs.get(0).get(); var second = jobs.get(1).get();
            ids.add(first.record().id()); ids.add(second.record().id());
            assertThat(first.record().id()).isEqualTo(second.record().id());
            assertThat(first.created()).isNotEqualTo(second.created());
        }
    }

    @Test
    @DisplayName("真实RR事务的并发冲突必须重开读视图，再返回同一快照")
    void retriesRealTransaction() throws Exception {
        var value = snapshot(date.minusDays(2), "2.00");
        var source = mock(GoldResearchSnapshotService.class);
        var read = new java.util.concurrent.CyclicBarrier(2);
        var committed = new java.util.concurrent.CountDownLatch(1);
        var delay = ThreadLocal.withInitial(() -> false);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(source.createSnapshot()).thenAnswer(invocation -> {
            jdbc.queryForObject("select count(*) from gold_research_snapshot", Long.class);
            int count = calls.incrementAndGet();
            if (count <= 2) {
                read.await(10, java.util.concurrent.TimeUnit.SECONDS);
                if (delay.get() && !committed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("测试等待第一事务提交超时");
            }
            return value;
        });
        var recorder = new GoldResearchSnapshotRecordingService(source, repository,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), transactions);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstJob = pool.submit(() -> {
                try { return recorder.recordCurrentSnapshot(); }
                finally { committed.countDown(); }
            });
            var secondJob = pool.submit(() -> {
                delay.set(true);
                return recorder.recordCurrentSnapshot();
            });
            var first = firstJob.get(20, java.util.concurrent.TimeUnit.SECONDS);
            ids.add(first.record().id());
            var second = secondJob.get(20, java.util.concurrent.TimeUnit.SECONDS);
            ids.add(second.record().id());
            assertThat(second.record().id()).isEqualTo(first.record().id());
            assertThat(first.created()).isTrue();
            assertThat(second.created()).isFalse();
            assertThat(calls.get()).isEqualTo(3);
        }
    }

    private GoldResearchSnapshot copy(GoldResearchSnapshot source, GoldSnapshotInput input) {
        return new GoldResearchSnapshot(source.analysisDate(), source.latestGoldDate(), source.latestRealRateDate(),
                source.latestDollarIndexDate(), source.gold(), source.realRate(), source.dollarIndex(),
                source.realRateAssessment(), source.dollarIndexAssessment(), source.researchVersion(), source.disclaimer(), input);
    }

    private SaveGoldResearchSnapshotResult save(GoldResearchSnapshot value) {
        var result = repository.saveIfAbsent(value, NOW);
        ids.add(result.record().id());
        return result;
    }

    private GoldResearchSnapshot snapshot(LocalDate macroDate, String rate) {
        var gold = mock(GoldDailyBarRepository.class);
        var macro = mock(MacroObservationRepository.class);
        List<GoldDailyBar> bars = new ArrayList<>();
        LocalDate day = date;
        while (day.getDayOfWeek().getValue() > 5) day = day.minusDays(1);
        for (int i = 0; i < 21; i++) {
            var price = new BigDecimal("2500").add(BigDecimal.valueOf(i));
            bars.add(new GoldDailyBar("XAUUSD", day, price, price.add(BigDecimal.TEN),
                    price.subtract(BigDecimal.TEN), price, "usd", "troy_ounce", "twelve_data", NOW.minusHours(1),
                    new GoldBarConfirmation(GoldBarConfirmation.SOURCE, date, NOW.minusMinutes(1), "a".repeat(64))));
            do { day = day.minusDays(1); } while (day.getDayOfWeek().getValue() > 5);
        }
        when(gold.findRecent("XAUUSD", "twelve_data", 120)).thenReturn(bars);
        when(macro.findRecent("DFII10", 120)).thenReturn(observations("DFII10", "percent", macroDate, rate));
        when(macro.findRecent("DTWEXBGS", 120)).thenReturn(observations("DTWEXBGS", "index_2006_100", macroDate, "120"));
        return new GoldResearchSnapshotService(gold, macro, new RealRateFactorEvaluator(),
                new DollarIndexFactorEvaluator(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)).createSnapshot();
    }

    private List<MacroObservation> observations(String series, String unit, LocalDate day, String value) {
        List<MacroObservation> rows = new ArrayList<>();
        for (int i = 0; i < 21; i++) rows.add(new MacroObservation(UUID.randomUUID(), series,
                day.minusDays(i), new BigDecimal(value).subtract(BigDecimal.valueOf(i, 2)),
                unit, "fred", NOW.minusHours(1), null));
        return rows;
    }
}
