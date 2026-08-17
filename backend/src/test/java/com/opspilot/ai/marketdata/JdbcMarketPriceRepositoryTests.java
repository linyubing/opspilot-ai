package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class JdbcMarketPriceRepositoryTests {

    private static final String TEST_SYMBOL = "XAUUSD_TEST";
    private static final String TEST_PROVIDER = "repository_test";
    private static final OffsetDateTime COLLECTED_AT =
            OffsetDateTime.of(2026, 8, 17, 8, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MarketPriceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTestData() {
        jdbcTemplate.update(
                "delete from market_price where provider = ? and symbol = ?",
                TEST_PROVIDER,
                TEST_SYMBOL
        );
    }

    @Test
    @DisplayName("同一天价格重复同步时更新而不新增记录")
    void upsertsSameDate() {
        repository.saveAll(List.of(price("2026-08-14", "100.00000000")));
        repository.saveAll(List.of(price("2026-08-14", "101.00000000")));

        assertThat(repository.findRecent(TEST_SYMBOL, 10))
                .singleElement()
                .extracting(MarketPrice::referencePrice)
                .isEqualTo(new BigDecimal("101.00000000"));
    }

    @Test
    @DisplayName("最新价格返回日期最大的一条记录")
    void findsLatestPrice() {
        repository.saveAll(List.of(
                price("2026-08-13", "99.00000000"),
                price("2026-08-14", "100.00000000")
        ));

        assertThat(repository.findLatest(TEST_SYMBOL))
                .hasValueSatisfying(result -> {
                    assertThat(result.priceDate())
                            .isEqualTo(LocalDate.of(2026, 8, 14));
                    assertThat(result.referencePrice())
                            .isEqualTo(new BigDecimal("100.00000000"));
                });
    }

    @Test
    @DisplayName("最近价格按照日期倒序返回并限制数量")
    void findsRecentPrices() {
        repository.saveAll(List.of(
                price("2026-08-12", "98.00000000"),
                price("2026-08-13", "99.00000000"),
                price("2026-08-14", "100.00000000")
        ));

        assertThat(repository.findRecent(TEST_SYMBOL, 2))
                .extracting(MarketPrice::priceDate)
                .containsExactly(
                        LocalDate.of(2026, 8, 14),
                        LocalDate.of(2026, 8, 13)
                );
    }

    @Test
    @DisplayName("查询数量必须大于零")
    void rejectsNonPositiveLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> repository.findRecent(TEST_SYMBOL, 0))
                .withMessage("limit 必须大于 0");
    }

    /**
     * 固定数字只验证 JDBC 存取契约，不代表真实黄金行情。
     */
    private MarketPrice price(String date, String value) {
        return new MarketPrice(
                TEST_SYMBOL,
                LocalDate.parse(date),
                new BigDecimal(value),
                "usd",
                "troy_ounce",
                TEST_PROVIDER,
                COLLECTED_AT
        );
    }
}
