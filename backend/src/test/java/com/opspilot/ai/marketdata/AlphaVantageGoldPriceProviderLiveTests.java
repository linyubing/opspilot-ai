package com.opspilot.ai.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ALPHA_VANTAGE_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "live.tests", matches = "true")
class AlphaVantageGoldPriceProviderLiveTests {

    @Test
    @DisplayName("从 Alpha Vantage 获取真实 XAU 每日参考价")
    void fetchesRealXauPrices() {
        MarketDataProperties properties = new MarketDataProperties(
                URI.create("https://www.alphavantage.co"),
                System.getenv("ALPHA_VANTAGE_API_KEY"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)
        );
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .build();
        GoldPriceProvider provider = new AlphaVantageGoldPriceProvider(
                restClient,
                properties,
                Clock.systemUTC()
        );

        List<MarketPrice> prices = provider.fetchDailyPrices();

        assertThat(prices).isNotEmpty();
        assertThat(prices)
                .allSatisfy(price -> {
                    assertThat(price.symbol()).isEqualTo("XAUUSD");
                    assertThat(price.referencePrice()).isPositive();
                    assertThat(price.provider()).isEqualTo("alpha_vantage");
                });
    }
}
