package com.opspilot.ai.marketdata;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 只读获取原生日线与UTC小时线，核验候选区间，不保存行情。 */
@Service
public class GoldSessionCheckService {
    private static final DateTimeFormatter HOUR_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private final RestClient client;
    private final TwelveDataProperties properties;
    private final Clock clock;
    private final GoldSessionCheck checker;

    public GoldSessionCheckService(@Qualifier("twelveDataRestClient") RestClient client,
            TwelveDataProperties properties, Clock clock, GoldSessionCheck checker) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.checker = checker;
    }

    public GoldSessionCheckResult check(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("黄金标签日期不能为空");
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new MarketDataUnavailableException("Twelve Data API Key 未配置");
        }
        try {
            GoldSession session = GoldSession.forDate(date);
            JsonNode daily = fetch("1day", date.toString(), date.plusDays(1).toString(), 2, false);
            validateRoot(daily, "1day");
            JsonNode row = selectDay(daily, date);
            BigDecimal open = decimal(row, "open"), high = decimal(row, "high");
            BigDecimal low = decimal(row, "low"), close = decimal(row, "close");
            if (!GoldHourBar.validPrices(open, high, low, close)) {
                throw new MarketDataUnavailableException("黄金原生日线价格关系无效");
            }
            JsonNode hourly = fetch("1h", REQUEST_TIME.format(session.start()), REQUEST_TIME.format(session.end()), 64, true);
            validateRoot(hourly, "1h");
            JsonNode outputZone = hourly.path("meta").path("timezone");
            if (!outputZone.isMissingNode() && !outputZone.isNull()
                    && (!outputZone.isTextual() || !"UTC".equals(outputZone.asText()))) {
                throw new MarketDataUnavailableException("黄金小时线响应时区与UTC请求冲突");
            }
            var bars = new ArrayList<GoldHourBar>();
            boolean endSeen = false;
            for (JsonNode item : hourly.path("values")) {
                // 固定请求UTC，按UTC起点解析；exchange_timezone不是响应输出时区的证明。
                Instant start = LocalDateTime.parse(text(item, "datetime"), HOUR_TIME).toInstant(ZoneOffset.UTC);
                var bar = new GoldHourBar(start, decimal(item, "open"), decimal(item, "high"),
                        decimal(item, "low"), decimal(item, "close"));
                // 仅排除接口可能额外包含的精确右端点，其余越界/重复交给核验器拒绝。
                if (start.equals(session.end())) {
                    if (endSeen) throw new MarketDataUnavailableException("黄金小时线结束端点重复");
                    endSeen = true;
                } else {
                    bars.add(bar);
                }
            }
            Instant checkedAt = clock.instant();
            var day = new GoldDailyBar("XAUUSD", date, open, high, low, close,
                    "usd", "troy_ounce", "twelve_data", checkedAt.atOffset(ZoneOffset.UTC));
            return checker.check(day, bars, checkedAt);
        } catch (MarketDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不附带原异常：网络异常通常含有apikey URL或响应原文。
            throw new MarketDataUnavailableException("黄金时段行情请求失败，请稍后重试");
        } catch (java.time.DateTimeException | IllegalArgumentException exception) {
            throw new MarketDataUnavailableException("黄金时段响应时间或价格格式无效");
        }
    }

    private JsonNode fetch(String interval, String start, String end, int size, boolean utc) {
        return client.get().uri(builder -> {
            builder.path("/time_series").queryParam("symbol", "XAU/USD").queryParam("interval", interval)
                    .queryParam("start_date", start).queryParam("end_date", end).queryParam("outputsize", size)
                    .queryParam("apikey", properties.apiKey());
            if (utc) builder.queryParam("timezone", "UTC");
            return builder.build();
        }).retrieve().body(JsonNode.class);
    }

    private void validateRoot(JsonNode root, String interval) {
        if (root == null || !root.isObject() || !"ok".equals(root.path("status").asText())
                || !"XAU/USD".equals(root.path("meta").path("symbol").asText())
                || !interval.equals(root.path("meta").path("interval").asText())
                || !root.path("values").isArray() || root.path("values").isEmpty()) {
            throw new MarketDataUnavailableException("黄金时段响应标识或数据结构无效");
        }
    }

    private JsonNode selectDay(JsonNode root, LocalDate date) {
        JsonNode selected = null;
        var seen = new HashSet<LocalDate>();
        for (JsonNode row : root.path("values")) {
            LocalDate label = LocalDate.parse(text(row, "datetime"));
            if (!seen.add(label)) throw new MarketDataUnavailableException("黄金原生日线标签重复");
            if (!label.equals(date) && !label.equals(date.plusDays(1))) {
                throw new MarketDataUnavailableException("黄金原生日线标签超出请求范围");
            }
            if (label.equals(date)) selected = row;
        }
        if (selected == null) throw new MarketDataUnavailableException("黄金原生日线缺少目标标签");
        return selected;
    }

    private String text(JsonNode row, String field) {
        if (!row.path(field).isTextual() || row.path(field).asText().isBlank()) {
            throw new MarketDataUnavailableException("黄金时段响应缺少有效字段：" + field);
        }
        return row.path(field).asText();
    }

    private BigDecimal decimal(JsonNode row, String field) {
        // 字符串直接转十进制；拒绝数字节点，避免Double转换带来的精度损失。
        return new BigDecimal(text(row, field));
    }
}
