package com.opspilot.ai.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** 解析 Twelve Data 返回的 XAU/USD 日线，暂不负责持久化。 */
@Component
public class TwelveDataGoldBarProvider {

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "twelve_data";

    private RestClient restClient;
    private TwelveDataProperties properties;
    private Clock clock;

    TwelveDataGoldBarProvider() {
    }

    @Autowired
    public TwelveDataGoldBarProvider(
            @Qualifier("twelveDataRestClient") RestClient restClient,
            TwelveDataProperties properties,
            Clock clock
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    public List<GoldDailyBar> fetchDailyBars() {
        if (properties.apiKey() == null
                || properties.apiKey().isBlank()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data API Key 未配置"
            );
        }
        try {
            JsonNode root = restClient.get()
                    .uri(builder -> builder
                            .path("/time_series")
                            .queryParam("symbol", "XAU/USD")
                            .queryParam("interval", "1day")
                            .queryParam("outputsize", 5000)
                            .queryParam("apikey", properties.apiKey())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root, OffsetDateTime.now(clock));
        } catch (MarketDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金日线请求失败",
                    exception
            );
        }
    }

    List<GoldDailyBar> parse(JsonNode root, OffsetDateTime collectedAt) {
        validateRoot(root);
        List<GoldDailyBar> bars = new ArrayList<>();
        for (JsonNode item : root.get("values")) {
            BigDecimal open = decimal(item, "open");
            BigDecimal high = decimal(item, "high");
            BigDecimal low = decimal(item, "low");
            BigDecimal close = decimal(item, "close");
            validatePrices(open, high, low, close);
            bars.add(new GoldDailyBar(
                    SYMBOL,
                    LocalDate.parse(text(item, "datetime")),
                    open,
                    high,
                    low,
                    close,
                    "usd",
                    "troy_ounce",
                    PROVIDER,
                    collectedAt
            ));
        }
        return List.copyOf(bars);
    }

    private void validateRoot(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 返回空响应"
            );
        }
        if (!"ok".equals(root.path("status").asText())) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 返回错误："
                            + root.path("message").asText("未知错误")
            );
        }
        if (!"XAU/USD".equals(root.path("meta").path("symbol").asText())) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金标的不匹配"
            );
        }
        if (!root.path("values").isArray()
                || root.path("values").isEmpty()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金日线为空"
            );
        }
    }

    private void validatePrices(
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {
        if (low.signum() <= 0
                || high.compareTo(open) < 0
                || high.compareTo(close) < 0
                || low.compareTo(open) > 0
                || low.compareTo(close) > 0) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金 OHLC 价格关系无效"
            );
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        try {
            return new BigDecimal(text(node, field));
        } catch (NumberFormatException exception) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 字段格式错误：" + field,
                    exception
            );
        }
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 缺少字段：" + field
            );
        }
        return value;
    }
}
