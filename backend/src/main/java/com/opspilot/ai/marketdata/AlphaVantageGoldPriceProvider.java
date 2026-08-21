package com.opspilot.ai.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class AlphaVantageGoldPriceProvider implements GoldPriceProvider {

    private static final Logger log = LoggerFactory.getLogger(AlphaVantageGoldPriceProvider.class);

    private static final String SYMBOL = "XAUUSD";
    private static final String PROVIDER = "alpha_vantage";
    private static final String CURRENCY = "usd";
    private static final String UNIT = "troy_ounce";

    private final RestClient restClient;
    private final MarketDataProperties properties;
    private final Clock clock;

    public AlphaVantageGoldPriceProvider(
            @Qualifier("alphaVantageRestClient") RestClient restClient,
            MarketDataProperties properties,
            Clock clock
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<MarketPrice> fetchDailyPrices() {
        validateApiKey();

        long startedAt = System.nanoTime();
        OffsetDateTime collectedAt = OffsetDateTime.now(clock);

        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/query")
                            .queryParam("function","GOLD_SILVER_HISTORY")
                            .queryParam("symbol","XAU")
                            .queryParam("interval","daily")
                            .queryParam("apikey",properties.apiKey())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            validateResponse(root);

            List<MarketPrice> prices = parsePrices(root,collectedAt);

            long elapsedMillis = (System.nanoTime() -startedAt) /1_000_000;

            LocalDate earliestDate = prices.stream()
                    .map(MarketPrice::priceDate)
                    .min(LocalDate::compareTo)
                    .orElseThrow();

            LocalDate latestDate = prices.stream()
                    .map(MarketPrice::priceDate)
                    .max(LocalDate::compareTo)
                    .orElseThrow();
            /*
             * 日志只记录数量、日期范围和耗时。
             * 不允许记录请求地址，因为请求地址中包含 API Key。
             */
            log.info(
                    "黄金历史价格获取完成，记录数={}，最早日期={}，最新日期={}，耗时={}毫秒",
                    prices.size(),
                    earliestDate,
                    latestDate,
                    elapsedMillis
            );

            return List.copyOf(prices);
        } catch (MarketDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new MarketDataUnavailableException(
                    "Alpha Vantage 黄金价格请求失败",
                    exception
            );
        }
    }

    private List<MarketPrice> parsePrices(
            JsonNode root,
            OffsetDateTime collectedAt
    ) {
        List<MarketPrice> prices = new ArrayList<>();

        for (JsonNode item : root.get("data")) {
            String dateText = requiredText(item, "date");
            String priceText = requiredText(item, "price");

            try {
                LocalDate priceDate = LocalDate.parse(dateText);
                BigDecimal referencePrice =
                        new BigDecimal(priceText);

                if (referencePrice.signum() <= 0) {
                    throw new MarketDataUnavailableException(
                            "黄金参考价格必须大于 0，日期：" + dateText
                    );
                }

                prices.add(new MarketPrice(
                        SYMBOL,
                        priceDate,
                        referencePrice,
                        CURRENCY,
                        UNIT,
                        PROVIDER,
                        collectedAt
                ));
            } catch (DateTimeParseException
                     | NumberFormatException exception) {
                /*
                 * 任意一条数据格式错误时拒绝整批响应，
                 * 防止部分错误数据进入数据库。
                 */
                throw new MarketDataUnavailableException(
                        "黄金价格格式错误，日期：" + dateText,
                        exception
                );
            }
        }

        return prices;
    }

    /**
     * Alpha Vantage 部分错误会使用 HTTP 200 返回，
     * 所以不能只检查 HTTP 状态，还必须检查 JSON 业务字段。
     */
    private void validateResponse(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new MarketDataUnavailableException(
                    "Alpha Vantage 返回空响应"
            );
        }

        String upstreamMessage = findUpstreamMessage(root);
        if (upstreamMessage != null) {
            throw new MarketDataUnavailableException(
                    "Alpha Vantage 返回错误：" + upstreamMessage
            );
        }

        String nominal = requiredText(root, "nominal");
        if (!SYMBOL.equals(nominal)) {
            throw new MarketDataUnavailableException(
                    "黄金标的不匹配，实际返回：" + nominal
            );
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new MarketDataUnavailableException(
                    "Alpha Vantage 黄金历史价格为空"
            );
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);

        if (field == null
                || !field.isTextual()
                || field.asText().isBlank()) {
            throw new MarketDataUnavailableException(
                    "Alpha Vantage 缺少字段：" + fieldName
            );
        }

        return field.asText();
    }

    /**
     * 查找 Alpha Vantage 常见的业务错误字段。
     */
    private String findUpstreamMessage(JsonNode root) {
        String[] errorFields = {
                "Information",
                "Note",
                "Error Message"
        };

        for (String fieldName : errorFields) {
            JsonNode field = root.get(fieldName);

            if (field != null
                    && field.isTextual()
                    && !field.asText().isBlank()) {
                return field.asText();
            }
        }

        return null;
    }

    /**
     * 在发起网络请求前检查key,避免向外部平台发送无效请求
     */
    private void validateApiKey() {
        if(properties.apiKey() == null || properties.apiKey().isBlank()){
            throw new MarketDataUnavailableException(
                    "未配置 ALPHA_VANTAGE_API_KEY"
            );
        }
    }
}
