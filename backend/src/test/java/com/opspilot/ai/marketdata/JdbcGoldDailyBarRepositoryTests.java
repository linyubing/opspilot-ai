package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcGoldDailyBarRepositoryTests {

    private static final String PROVIDER = "ohlc_repository_test";

    @Autowired
    private GoldDailyBarRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

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
