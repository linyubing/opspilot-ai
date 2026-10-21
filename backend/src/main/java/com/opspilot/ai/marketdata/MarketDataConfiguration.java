package com.opspilot.ai.marketdata;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        MarketDataProperties.class,
        TwelveDataProperties.class
})
public class MarketDataConfiguration {

    @Bean
    RestClient alphaVantageRestClient(MarketDataProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    RestClient twelveDataRestClient(TwelveDataProperties properties) {
        // 同一客户端的报价、日线与时段检查共享预算；不自动重试429。
        TwelveDataPacer pacer = new TwelveDataPacer(java.time.Duration.ofSeconds(9));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    // 锁覆盖实际发送，防止取得时隙的线程暂停后与后续请求挤在一起。
                    synchronized (pacer) {
                        pacer.acquire();
                        return execution.execute(request, body);
                    }
                })
                .build();
    }

    @Bean
    Clock marketDataClock() {
        return Clock.systemUTC();
    }
}
