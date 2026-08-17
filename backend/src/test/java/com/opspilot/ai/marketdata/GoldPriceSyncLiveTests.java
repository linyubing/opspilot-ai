package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
@EnabledIfEnvironmentVariable(named = "ALPHA_VANTAGE_API_KEY", matches = ".+")
class GoldPriceSyncLiveTests {

    @Autowired
    private GoldPriceSyncService service;

    @Autowired
    private MarketPriceRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("真实黄金价格同步后保存到 PostgreSQL 并排除周末")
    void syncsRealPricesWithoutWeekends() {
        GoldPriceSyncResult result = service.syncDailyPrices();

        assertThat(result.receivedCount()).isPositive();
        assertThat(result.savedCount()).isPositive();
        assertThat(result.weekendSkippedCount()).isPositive();
        assertThat(repository.findLatest("XAUUSD"))
                .hasValueSatisfying(price ->
                        assertThat(price.priceDate())
                                .isEqualTo(result.latestPriceDate())
                );

        Long weekendCount = jdbcTemplate.queryForObject("""
                select count(*)
                from market_price
                where provider = 'alpha_vantage'
                  and extract(isodow from price_date) in (6, 7)
                """, Long.class);

        assertThat(weekendCount).isZero();
    }
}
