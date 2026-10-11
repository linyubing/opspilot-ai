package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcGoldDailyBarRepositoryTests {

    private static final String PROVIDER = "ohlc_repo_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 30);

    @Autowired
    private GoldDailyBarRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("delete from gold_daily_bar where provider = ?", PROVIDER);
    }

    @Test
    void upsertsAndFindsLatestBar() {
        repository.saveAll(List.of(bar("2026-08-28", "4456.4")));
        repository.saveAll(List.of(bar("2026-08-28", "4457.8")));

        assertThat(repository.findLatest("XAUUSD", PROVIDER))
                .hasValueSatisfying(result -> {
                    assertThat(result.priceDate())
                            .isEqualTo(LocalDate.parse("2026-08-28"));
                    assertThat(result.close())
                            .isEqualByComparingTo("4457.8");
                });
    }

    @Test
    void findsRecentWithoutFutureBars() {
        repository.saveAll(List.of(
                bar("2026-08-27", "4400"),
                bar("2026-08-28", "4456"),
                bar("2026-09-01", "4500")
        ));

        List<GoldDailyBar> bars = repository.findRecent(
                "XAUUSD",
                PROVIDER,
                LocalDate.parse("2026-08-28"),
                2
        );

        assertThat(bars)
                .extracting(GoldDailyBar::priceDate)
                .containsExactly(
                        LocalDate.parse("2026-08-28"),
                        LocalDate.parse("2026-08-27")
                );
    }

    @Test
    void findsAllInDateOrder() {
        repository.saveAll(List.of(
                bar("2026-08-28", "4456"),
                bar("2026-08-27", "4400")
        ));

        assertThat(repository.findAll("XAUUSD", PROVIDER))
                .extracting(GoldDailyBar::priceDate)
                .containsExactly(
                        LocalDate.parse("2026-08-27"),
                        LocalDate.parse("2026-08-28")
                );
    }

    @Test
    void findsNextRealBar() {
        repository.saveAll(List.of(
                bar("2026-08-28", "4456"),
                bar("2026-09-01", "4500")
        ));

        assertThat(repository.findNext(
                "XAUUSD",
                PROVIDER,
                LocalDate.parse("2026-08-28")
        )).hasValueSatisfying(next -> {
            assertThat(next.priceDate())
                    .isEqualTo(LocalDate.parse("2026-09-01"));
            assertThat(next.close()).isEqualByComparingTo("4500");
        });
    }

    @Test
    void findsBarsAfterDateInOrder() {
        repository.saveAll(List.of(
                bar("2026-08-27", "4400"),
                bar("2026-08-28", "4456"),
                bar("2026-09-01", "4500")
        ));

        assertThat(repository.findAfter(
                "XAUUSD", PROVIDER,
                LocalDate.parse("2026-08-27"), 2
        )).extracting(GoldDailyBar::priceDate)
                .containsExactly(
                        LocalDate.parse("2026-08-28"),
                        LocalDate.parse("2026-09-01")
                );
    }

    @Test
    void persistsConfirmationOnEveryReadPath() {
        repository.saveAll(List.of(confirmed("2026-08-28", "4456")));
        LocalDate before = LocalDate.parse("2026-08-27");
        var proof = confirmed("2026-08-28", "4456").confirmation();
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().confirmation()).isEqualTo(proof);
        assertThat(repository.findNext("XAUUSD", PROVIDER, before).orElseThrow().confirmation()).isEqualTo(proof);
        assertThat(repository.findRecent("XAUUSD", PROVIDER, 1).getFirst().confirmation()).isEqualTo(proof);
        assertThat(repository.findRecent("XAUUSD", PROVIDER, before.plusDays(1), 1).getFirst().confirmation()).isEqualTo(proof);
        assertThat(repository.findAll("XAUUSD", PROVIDER).getFirst().confirmation()).isEqualTo(proof);
        assertThat(repository.findAfter("XAUUSD", PROVIDER, before, 1).getFirst().confirmation()).isEqualTo(proof);
    }

    @Test
    void rawOverwriteClearsPreviousProof() {
        repository.saveAll(List.of(confirmed("2026-08-28", "4456")));
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().confirmation()).isNotNull();
        repository.saveAll(List.of(bar("2026-08-28", "4457")));
        var latest = repository.findLatest("XAUUSD", PROVIDER).orElseThrow();
        assertThat(latest.close()).isEqualByComparingTo("4457");
        assertThat(latest.confirmation()).isNull();
    }

    @Test
    void rollsBackWholeBatchWhenLaterRowFails() {
        var invalid = new GoldDailyBar("XAUUSD", LocalDate.parse("2026-08-29"),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                "eur", "troy_ounce", PROVIDER, OffsetDateTime.parse("2026-08-31T00:00:00Z"));
        assertThatThrownBy(() -> repository.saveAll(List.of(confirmed("2026-08-28", "4456"), invalid)))
                .isInstanceOf(RuntimeException.class);
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @Test
    void rejectsPriceRoundingOfConfirmedData() {
        assertThatThrownBy(() -> repository.saveAll(List.of(confirmed("2026-08-28", "4456.123456789"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @Test
    void saveMissingInsertsOnlyMissingConfirmedDays() {
        repository.saveAll(List.of(confirmed("2026-08-28", "4456")));

        int inserted = repository.saveMissing(List.of(
                confirmed("2026-08-27", "4400"), confirmed("2026-08-28", "4456")));

        assertThat(inserted).isEqualTo(1);
        assertThat(repository.findAll("XAUUSD", PROVIDER))
                .extracting(GoldDailyBar::priceDate)
                .containsExactly(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-28"));
        assertThat(repository.findAll("XAUUSD", PROVIDER))
                .allSatisfy(saved -> assertThat(saved.confirmation()).isNotNull());
    }

    @Test
    void saveMissingSameValuesKeepsOriginalCollectionAndProof() {
        var original = confirmed("2026-08-28", "4456");
        repository.saveAll(List.of(original));
        var refreshed = withProof(original, OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                LocalDate.parse("2026-08-31"), "cd".repeat(32));

        assertThat(repository.saveMissing(List.of(refreshed))).isZero();

        var saved = repository.findLatest("XAUUSD", PROVIDER).orElseThrow();
        assertThat(saved.collectedAt()).isEqualTo(OffsetDateTime.parse("2026-08-31T00:00:00Z"));
        assertThat(saved.confirmation()).isEqualTo(original.confirmation());
        assertThat(saved.close()).isEqualByComparingTo("4456");
    }

    @Test
    void saveMissingLaterConflictRollsBackPreviouslyInsertedDay() {
        repository.saveAll(List.of(confirmed("2026-08-28", "4456")));

        assertThatThrownBy(() -> repository.saveMissing(List.of(
                confirmed("2026-08-27", "4400"), confirmed("2026-08-28", "4457"))))
                .isInstanceOf(MarketDataUnavailableException.class);

        assertThat(repository.findAll("XAUUSD", PROVIDER))
                .extracting(GoldDailyBar::priceDate).containsExactly(LocalDate.parse("2026-08-28"));
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().close())
                .isEqualByComparingTo("4456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "high", "low", "close"})
    void saveMissingNeverOverwritesAnyPrice(String changedPrice) {
        var original = confirmed("2026-08-28", "4456");
        repository.saveAll(List.of(original));
        var revised = new GoldDailyBar(original.symbol(), original.priceDate(),
                changedPrice.equals("open") ? new BigDecimal("4457") : original.open(),
                changedPrice.equals("high") ? new BigDecimal("4467") : original.high(),
                changedPrice.equals("low") ? new BigDecimal("4445") : original.low(),
                changedPrice.equals("close") ? new BigDecimal("4457") : original.close(),
                original.currency(), original.unit(), original.provider(), original.collectedAt(),
                original.confirmation());

        assertThatThrownBy(() -> repository.saveMissing(List.of(revised)))
                .isInstanceOf(MarketDataUnavailableException.class);

        var saved = repository.findLatest("XAUUSD", PROVIDER).orElseThrow();
        assertThat(saved.open()).isEqualByComparingTo("4456");
        assertThat(saved.high()).isEqualByComparingTo("4466");
        assertThat(saved.low()).isEqualByComparingTo("4446");
        assertThat(saved.close()).isEqualByComparingTo("4456");
        assertThat(saved.collectedAt()).isEqualTo(original.collectedAt());
        assertThat(saved.confirmation()).isEqualTo(original.confirmation());
    }

    @Test
    void saveMissingCannotConfirmExistingUnconfirmedDay() {
        repository.saveAll(List.of(bar("2026-08-28", "4456")));

        assertThatThrownBy(() -> repository.saveMissing(List.of(confirmed("2026-08-28", "4456"))))
                .isInstanceOf(MarketDataUnavailableException.class);

        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().confirmation()).isNull();
    }

    @Test
    void saveMissingRejectsPrecisionBeforeWritingAnyRow() {
        assertThatThrownBy(() -> repository.saveMissing(List.of(
                confirmed("2026-08-27", "4400"), confirmed("2026-08-28", "4456.123456789"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @Test
    void saveMissingRejectsDuplicateInputKeys() {
        assertThatThrownBy(() -> repository.saveMissing(List.of(
                confirmed("2026-08-28", "4456"), confirmed("2026-08-28", "4456"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @Test
    void saveMissingRejectsUnconfirmedInput() {
        assertThatThrownBy(() -> repository.saveMissing(List.of(bar("2026-08-28", "4456"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @Test
    void saveMissingRejectsInvalidConfirmationBeforeWriting() {
        var raw = confirmed("2026-08-28", "4456");
        var invalid = withProof(raw, raw.collectedAt(), LocalDate.parse("2026-08-28"), "invalid");

        assertThatThrownBy(() -> repository.saveMissing(List.of(invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @Test
    void saveMissingEmptyBatchReturnsZero() {
        assertThat(repository.saveMissing(List.of())).isZero();
        assertThat(repository.findAll("XAUUSD", PROVIDER)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"currency", "unit"})
    void saveMissingRejectsDifferentPriceBasis(String changedBasis) {
        var original = confirmed("2026-08-28", "4456");
        repository.saveAll(List.of(original));
        var revised = new GoldDailyBar(original.symbol(), original.priceDate(), original.open(),
                original.high(), original.low(), original.close(),
                changedBasis.equals("currency") ? "eur" : original.currency(),
                changedBasis.equals("unit") ? "gram" : original.unit(), original.provider(),
                original.collectedAt(), original.confirmation());

        assertThatThrownBy(() -> repository.saveMissing(List.of(revised)))
                .isInstanceOf(MarketDataUnavailableException.class);
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().currency()).isEqualTo("usd");
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().unit()).isEqualTo("troy_ounce");
    }

    @Test
    void saveMissingConcurrentSameValuesRemainIdempotent() throws Exception {
        var original = confirmed("2026-08-28", "4456");
        var incoming = withProof(original, OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                LocalDate.parse("2026-08-31"), "cd".repeat(32));

        assertThat(saveMissingAgainstConcurrentWriter(original, List.of(incoming))).isZero();

        var saved = repository.findLatest("XAUUSD", PROVIDER).orElseThrow();
        assertThat(saved.collectedAt()).isEqualTo(OffsetDateTime.parse("2026-08-31T00:00:00Z"));
        assertThat(saved.confirmation()).isEqualTo(original.confirmation());
    }

    @Test
    void saveMissingConcurrentRevisionRollsBackEarlierMissingDay() {
        var original = confirmed("2026-08-28", "4456");

        assertThatThrownBy(() -> saveMissingAgainstConcurrentWriter(original, List.of(
                confirmed("2026-08-27", "4400"), confirmed("2026-08-28", "4457"))))
                .hasCauseInstanceOf(MarketDataUnavailableException.class);

        assertThat(repository.findAll("XAUUSD", PROVIDER))
                .extracting(GoldDailyBar::priceDate).containsExactly(LocalDate.parse("2026-08-28"));
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().close())
                .isEqualByComparingTo("4456");
    }

    @Test
    void saveMissingConcurrentUnconfirmedDayIsNotStamped() {
        var original = bar("2026-08-28", "4456");

        assertThatThrownBy(() -> saveMissingAgainstConcurrentWriter(original,
                List.of(confirmed("2026-08-28", "4456"))))
                .hasCauseInstanceOf(MarketDataUnavailableException.class);

        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().confirmation()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "closed_day = null", "confirmed_at = null", "confirmation_source = null",
            "confirmation_hash = null", "confirmation_hash = 'gg'",
            "confirmation_source = 'unknown'", "closed_day = '2026-08-27'",
            "confirmed_at = '2026-08-30T23:59:59Z'", "closed_day = '2026-09-01'"
    })
    void databaseRejectsBrokenProof(String assignment) {
        repository.saveAll(List.of(confirmed("2026-08-28", "4456")));
        // assignment 仅来自上面的测试常量，不接受用户或接口输入。
        assertThatThrownBy(() -> jdbc.update("update gold_daily_bar set " + assignment
                + " where provider = ?", PROVIDER))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findLatest("XAUUSD", PROVIDER).orElseThrow().confirmation())
                .isEqualTo(confirmed("2026-08-28", "4456").confirmation());
    }

    private GoldDailyBar confirmed(String date, String close) {
        var raw = bar(date, close);
        return new GoldDailyBar(raw.symbol(), raw.priceDate(), raw.open(), raw.high(), raw.low(),
                raw.close(), raw.currency(), raw.unit(), raw.provider(), raw.collectedAt(),
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, LocalDate.parse("2026-08-28"),
                        raw.collectedAt(), "ab".repeat(32)));
    }

    private GoldDailyBar withProof(GoldDailyBar bar, OffsetDateTime collectedAt,
            LocalDate closedDay, String receiptHash) {
        return new GoldDailyBar(bar.symbol(), bar.priceDate(), bar.open(), bar.high(), bar.low(),
                bar.close(), bar.currency(), bar.unit(), bar.provider(), collectedAt,
                new GoldBarConfirmation(GoldBarConfirmation.SOURCE, closedDay, collectedAt, receiptHash));
    }

    private int saveMissingAgainstConcurrentWriter(GoldDailyBar original, List<GoldDailyBar> incoming)
            throws Exception {
        var inserted = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var competingPid = new CompletableFuture<Integer>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                repository.saveAll(List.of(original));
                inserted.countDown();
                try {
                    if (!releaseCommit.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发测试未释放写事务");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("并发测试被中断", exception);
                }
                return 1;
            }));
            try {
                assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
                var contender = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    competingPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return repository.saveMissing(incoming);
                }));
                try {
                    assertWaitingForConcurrentTransaction(competingPid.get(5, TimeUnit.SECONDS));
                } finally {
                    releaseCommit.countDown();
                }
                assertThat(writer.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                return contender.get(5, TimeUnit.SECONDS);
            } finally {
                releaseCommit.countDown();
            }
        }
    }

    private void assertWaitingForConcurrentTransaction(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    select exists (
                        select 1 from pg_stat_activity
                        where pid = ? and wait_event_type = 'Lock' and wait_event = 'transactionid'
                    )
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("补日事务未进入实际 PostgreSQL 并发冲突等待");
    }

    private GoldDailyBar bar(String date, String close) {
        BigDecimal closePrice = new BigDecimal(close);
        return new GoldDailyBar(
                "XAUUSD",
                LocalDate.parse(date),
                closePrice,
                closePrice.add(BigDecimal.TEN),
                closePrice.subtract(BigDecimal.TEN),
                closePrice,
                "usd",
                "troy_ounce",
                PROVIDER,
                OffsetDateTime.parse("2026-08-31T00:00:00Z")
        );
    }
}
