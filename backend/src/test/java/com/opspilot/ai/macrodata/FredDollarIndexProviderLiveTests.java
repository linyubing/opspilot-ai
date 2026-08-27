package com.opspilot.ai.macrodata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "FRED_API_KEY", matches = ".+")
class FredDollarIndexProviderLiveTests {

    @Test
    @DisplayName("从 FRED 获取真实 DTWEXBGS 广义美元指数")
    void fetchesRealBroadDollarIndexObservations() {
        FredProperties properties = new FredProperties(
                URI.create("https://api.stlouisfed.org"),
                System.getenv("FRED_API_KEY"),
                "DFII10",
                "DTWEXBGS",
                Duration.ofSeconds(5),
                Duration.ofSeconds(20)
        );
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .build();
        DollarIndexProvider provider = new FredDollarIndexProvider(
                new FredSeriesClient(restClient, properties),
                properties
        );

        DollarIndexBatch batch = provider.fetchDailyObservations();

        // 真实数据会持续变化，因此这里只验证来源合同，不固定日期和数值。
        assertThat(batch.receivedCount()).isPositive();
        assertThat(batch.observations()).isNotEmpty();
        assertThat(batch.observations()).allSatisfy(observation -> {
            assertThat(observation.seriesId()).isEqualTo("DTWEXBGS");
            assertThat(observation.observationDate()).isNotNull();
            assertThat(observation.value()).isNotNull();
            assertThat(observation.unit()).isEqualTo("index_2006_100");
            assertThat(observation.provider()).isEqualTo("fred");
        });
    }
}
