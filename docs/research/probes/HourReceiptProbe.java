import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldHourBar;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.GoldSessionCheck;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** 离线交叉核验真实逐行日线与小时覆盖；保留重复标签，不修改行情。 */
public class HourReceiptProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("请提供真实日线包及小时线回执，可选前移一小时回执");
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var initial = json.readTree(Path.of(args[0]).toFile()).path("initial");
        var hourly = json.readTree(Path.of(args[1]).toFile());
        if (!"UTC".equals(hourly.path("request").path("timezone").asText())
                || !"ok".equals(hourly.path("response").path("status").asText())
                || !"1h".equals(hourly.path("response").path("meta").path("interval").asText())
                || !"XAU/USD".equals(hourly.path("response").path("meta").path("symbol").asText())) {
            throw new IllegalArgumentException("小时回执请求或标识不符");
        }
        var checkedAt = OffsetDateTime.parse(hourly.path("completedAt").asText()).toInstant();
        var daysReceived = OffsetDateTime.parse(initial.path("completedAt").asText());
        var hours = new ArrayList<GoldHourBar>();
        for (var row : hourly.path("response").path("values")) {
            var start = LocalDateTime.parse(row.path("datetime").asText(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
            hours.add(new GoldHourBar(start, decimal(row, "open"), decimal(row, "high"),
                    decimal(row, "low"), decimal(row, "close")));
        }
        var outputs = new ArrayList<Object>();
        int index = 0;
        for (var row : initial.path("bars").path("values")) {
            var date = LocalDate.parse(row.path("datetime").asText());
            var session = GoldSession.forDate(date);
            var selected = hours.stream().filter(h -> !h.start().isBefore(session.start())
                    && h.start().isBefore(session.end())).toList();
            var day = new GoldDailyBar("XAUUSD", date, decimal(row, "open"), decimal(row, "high"),
                    decimal(row, "low"), decimal(row, "close"), "usd", "troy_ounce", "twelve_data", daysReceived);
            var result = new GoldSessionCheck().check(day, selected, checkedAt);
            var output = new LinkedHashMap<String, Object>();
            output.put("sourceRow", index++);
            output.put("result", result);
            output.put("officialSessionCertified", false);
            if (!checkedAt.isBefore(session.end()) && selected.size() == result.expectedHours()
                    && result.missingHours().isEmpty()) output.put("candidateFieldMatches", matches(day, selected));
            if (args.length == 3 && date.equals(LocalDate.of(2026, 10, 3))) {
                var extra = json.readTree(Path.of(args[2]).toFile());
                if (!"UTC".equals(extra.path("request").path("timezone").asText())) {
                    throw new IllegalArgumentException("前移小时回执不是UTC请求");
                }
                var extended = new ArrayList<>(selected);
                for (var item : extra.path("response").path("values")) {
                    var start = LocalDateTime.parse(item.path("datetime").asText(),
                            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
                    // 只取新请求左端点；不合并重复的右端点，不改已有行。
                    if (start.equals(session.start().minusSeconds(3600))) extended.add(new GoldHourBar(start,
                            decimal(item, "open"), decimal(item, "high"), decimal(item, "low"), decimal(item, "close")));
                }
                var distinct = extended.stream().map(GoldHourBar::start).distinct().count();
                if (extended.size() != 24 || distinct != 24) throw new IllegalArgumentException("24小时假设缺行或重复");
                output.put("earlier24HourHypothesisStart", session.start().minusSeconds(3600));
                output.put("earlier24HourFieldMatches", matches(day, extended));
                output.put("hypothesisOnlyNotNewSessionRule", true);
            }
            outputs.add(output);
        }
        System.out.println(json.writeValueAsString(outputs));
    }

    private static java.util.Map<String, Boolean> matches(GoldDailyBar day, java.util.List<GoldHourBar> hours) {
        var sorted = hours.stream().sorted(java.util.Comparator.comparing(GoldHourBar::start)).toList();
        return java.util.Map.of("open", day.open().compareTo(sorted.getFirst().open()) == 0,
                "close", day.close().compareTo(sorted.getLast().close()) == 0,
                "high", day.high().compareTo(hours.stream().map(GoldHourBar::high).max(BigDecimal::compareTo).orElseThrow()) == 0,
                "low", day.low().compareTo(hours.stream().map(GoldHourBar::low).min(BigDecimal::compareTo).orElseThrow()) == 0);
    }

    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode row, String key) {
        if (!row.path(key).isTextual()) throw new IllegalArgumentException("价格不是原始十进制字符串");
        return new BigDecimal(row.path(key).asText());
    }
}
