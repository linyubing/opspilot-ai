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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 获取黄金日线，并以供应商闭市日报价校验可同步边界；不负责持久化。 */
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
            // /eod 的名称或 is_market_open 字段不能证明某根日线已经结束。
            JsonNode quote = restClient.get()
                    .uri(builder -> builder
                            .path("/quote")
                            .queryParam("symbol", "XAU/USD")
                            .queryParam("eod", true)
                            .queryParam("apikey", properties.apiKey())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            OffsetDateTime checkedAt = OffsetDateTime.now(clock);
            List<GoldDailyBar> bars = parse(root, checkedAt);
            return confirmed(bars, quote, checkedAt);
        } catch (MarketDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new MarketDataUnavailableException(
                    // 不附带 RestClient 原始异常，避免带 apikey 的 URL 进入错误日志。
                    "Twelve Data 黄金日线或闭市确认请求失败"
            );
        }
    }

    List<GoldDailyBar> parse(JsonNode root, OffsetDateTime collectedAt) {
        validateRoot(root);
        List<GoldDailyBar> bars = new ArrayList<>();
        var dates = new HashSet<LocalDate>();
        for (JsonNode item : root.get("values")) {
            BigDecimal open = decimal(item, "open");
            BigDecimal high = decimal(item, "high");
            BigDecimal low = decimal(item, "low");
            BigDecimal close = decimal(item, "close");
            validatePrices(open, high, low, close);
            LocalDate date = date(item);
            if (!dates.add(date)) {
                throw new MarketDataUnavailableException("Twelve Data 黄金日线日期重复");
            }
            bars.add(new GoldDailyBar(
                    SYMBOL,
                    date,
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

    private List<GoldDailyBar> confirmed(
            List<GoldDailyBar> bars, JsonNode quote, OffsetDateTime checkedAt
    ) {
        if (quote == null || !quote.isObject()
                || (quote.has("status") && !"ok".equals(quote.path("status").asText()))) {
            throw new MarketDataUnavailableException("Twelve Data 已结束交易日确认响应无效");
        }
        if (!"XAU/USD".equals(quote.path("symbol").asText())) {
            throw new MarketDataUnavailableException("Twelve Data 已结束交易日标的不匹配");
        }
        LocalDate closedDay = date(quote);
        if (closedDay.isAfter(checkedAt.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate())) {
            throw new MarketDataUnavailableException("Twelve Data 确认日不能是未来日期");
        }
        BigDecimal open = decimal(quote, "open");
        BigDecimal high = decimal(quote, "high");
        BigDecimal low = decimal(quote, "low");
        BigDecimal close = decimal(quote, "close");
        validatePrices(open, high, low, close);
        GoldDailyBar anchor = bars.stream()
                .filter(bar -> bar.priceDate().equals(closedDay))
                .findFirst()
                .orElseThrow(() -> new MarketDataUnavailableException("Twelve Data 日线缺少确认日"));
        // BigDecimal.compareTo 比较真实十进制值，不受 1.0/1.000 的精度位数影响。
        if (anchor.open().compareTo(open) != 0 || anchor.high().compareTo(high) != 0
                || anchor.low().compareTo(low) != 0 || anchor.close().compareTo(close) != 0) {
            throw new MarketDataUnavailableException("Twelve Data 确认日 OHLC 与日线不一致");
        }
        return bars.stream().filter(bar -> !bar.priceDate().isAfter(closedDay)).toList();
    }

    private LocalDate date(JsonNode node) {
        try {
            return LocalDate.parse(text(node, "datetime"));
        } catch (DateTimeParseException exception) {
            throw new MarketDataUnavailableException("Twelve Data 日期格式错误");
        }
    }

    private void validateRoot(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 返回空响应"
            );
        }
        if (!"ok".equals(root.path("status").asText())) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 黄金日线响应状态无效"
            );
        }
        if (!"1day".equals(root.path("meta").path("interval").asText())) {
            throw new MarketDataUnavailableException("Twelve Data 黄金日线周期不匹配");
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
        // 供应商合同使用十进制字符串；拒绝可能已经被 DoubleNode 舍入的数字节点。
        if (!node.path(field).isMissingNode() && !node.path(field).isTextual()) {
            throw new MarketDataUnavailableException("Twelve Data 字段类型错误：" + field);
        }
        try {
            return new BigDecimal(text(node, field));
        } catch (NumberFormatException exception) {
            throw new MarketDataUnavailableException(
                    "Twelve Data 字段格式错误：" + field
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
