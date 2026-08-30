package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
