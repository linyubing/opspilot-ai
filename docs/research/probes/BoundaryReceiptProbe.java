import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;

/** 用真实回执检验结束边界后移一小时假设；不修改生产时段或价格。 */
public class BoundaryReceiptProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("请提供真实复查回执");
        var json = new ObjectMapper();
        var receipts = json.readTree(Path.of(args[0]).toFile());
        var daily = receipts.get(0).path("response");
        var hourly = receipts.get(1).path("response");
        if (!"ok".equals(daily.path("status").asText())
                || !"ok".equals(hourly.path("status").asText())
                || !"1day".equals(daily.path("meta").path("interval").asText())
                || !"1h".equals(hourly.path("meta").path("interval").asText())
                || !"XAU/USD".equals(daily.path("meta").path("symbol").asText())
                || !"XAU/USD".equals(hourly.path("meta").path("symbol").asText())
                || !"UTC".equals(receipts.get(1).path("request").path("timezone").asText())
                || daily.path("values").size() != 1 || hourly.path("values").size() != 24) {
            throw new IllegalArgumentException("复查回执合同或数量不符");
        }
        var day = daily.path("values").get(0);
        if (!"2026-10-03".equals(day.path("datetime").asText())) throw new IllegalArgumentException("实验日期不符");
        var hours = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        hourly.path("values").forEach(hours::add);
        hours.sort(Comparator.comparing(row -> row.path("datetime").asText()));
        var start = LocalDateTime.of(2026, 10, 2, 21, 0).toInstant(ZoneOffset.UTC);
        var seen = new HashSet<java.time.Instant>();
        for (int i = 0; i < hours.size(); i++) {
            var time = LocalDateTime.parse(hours.get(i).path("datetime").asText(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
            if (!seen.add(time) || !time.equals(start.plusSeconds(i * 3600L))) {
                throw new IllegalArgumentException("小时线重复或覆盖不完整");
            }
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("hypothesisOnly", true);
        output.put("officialSessionCertified", false);
        output.put("start", start.toString());
        output.put("endExclusive", start.plusSeconds(86400).toString());
        output.put("hours", hours.size());
        output.put("openMatch", decimal(day, "open").compareTo(decimal(hours.getFirst(), "open")) == 0);
        output.put("highMatch", decimal(day, "high").compareTo(hours.stream().map(h -> decimal(h, "high")).max(BigDecimal::compareTo).orElseThrow()) == 0);
        output.put("lowMatch", decimal(day, "low").compareTo(hours.stream().map(h -> decimal(h, "low")).min(BigDecimal::compareTo).orElseThrow()) == 0);
        output.put("closeMatch", decimal(day, "close").compareTo(decimal(hours.getLast(), "close")) == 0);
        System.out.println(json.writeValueAsString(output));
    }

    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode row, String key) {
        if (!row.path(key).isTextual()) throw new IllegalArgumentException("价格必须是原始十进制字符串");
        return new BigDecimal(row.path(key).asText());
    }
}
