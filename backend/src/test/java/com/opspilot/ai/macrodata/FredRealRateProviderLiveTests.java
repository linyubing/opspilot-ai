package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "FRED_API_KEY", matches = ".+")
class FredRealRateProviderLiveTests {

    @Test
    @DisplayName("从 FRED 获取真实 DFII10 日度实际利率")
    void fetchesRealDfii10Observations() {
        FredProperties properties = new FredProperties(
                URI.create("https://api.stlouisfed.org"),
                System.getenv("FRED_API_KEY"),
                "DFII10",
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)
        );
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .build();
        RealRateProvider provider = new FredRealRateProvider(
                new FredSeriesClient(restClient, properties),
                properties
        );

        RealRateBatch batch = provider.fetchDailyObservations();

        /*
         * 真实利率会变化，因此只验证来源合同，
         * 不断言某个固定日期或固定数值。
         */
        assertThat(batch.receivedCount()).isPositive();
        assertThat(batch.observations()).isNotEmpty();
        assertThat(batch.observations())
                .allSatisfy(observation -> {
                    assertThat(observation.seriesId()).isEqualTo("DFII10");
                    assertThat(observation.observationDate()).isNotNull();
                    assertThat(observation.value()).isNotNull();
                    assertThat(observation.unit()).isEqualTo("percent");
                    assertThat(observation.provider()).isEqualTo("fred");
                });
    }
}
