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

    private GoldDailyBar bar(String date, String close) {
        return new GoldDailyBar(
                "XAUUSD",
                LocalDate.parse(date),
                new BigDecimal("4601.3"),
                new BigDecimal("4637.2"),
                new BigDecimal("4444.6"),
                new BigDecimal(close),
                "usd",
                "troy_ounce",
                PROVIDER,
                OffsetDateTime.parse("2026-08-31T00:00:00Z")
        );
    }
}
