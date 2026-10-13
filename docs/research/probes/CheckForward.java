import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 用生产时间与分类规则只读验收事前预测；输出研究证据，不改正式评分。 */
public class CheckForward {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需输入本地回执及新结果文件");
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var root = json.readTree(Path.of(args[0]).toFile());
        var f = root.path("forecast");
        var receipts = root.path("receipts");
        require(receipts.size() == 4, "回执数量不符");
        for (var receipt : receipts) {
            require(receipt.path("httpStatus").asInt() == 200 && !receipt.has("error"), "真实取数失败");
            String raw = receipt.path("rawResponse").asText();
            require(sha(raw.getBytes(StandardCharsets.UTF_8)).equals(receipt.path("rawSha256").asText()), "正文摘要不符");
            require(json.readTree(raw).equals(receipt.path("response")), "解析结果与正文不同");
        }
        require(f.path("id").asText().equals("56ff334e-8e5f-45aa-ab39-77833eb5e264"), "预测不是冻结记录");
        require(f.path("predicted_direction").asText().equals("neutral"), "原始方向变化");
        require(f.path("forecast_rule_version").asText().equals(GoldForecastRule.RULE_VERSION), "分类规则变化");
        var date = LocalDate.parse(f.path("promised_target_date").asText());
        require(date.equals(LocalDate.of(2026, 10, 12)), "目标日期变化");
        var session = GoldSession.forDate(date);
        require(session.start().equals(OffsetDateTime.parse(f.path("target_start").asText()).toInstant())
                && session.end().equals(OffsetDateTime.parse(f.path("target_end").asText()).toInstant()), "原始目标时段不同");
        require(OffsetDateTime.parse(f.path("created_at").asText()).toInstant().isBefore(session.start()), "非事前预测");
        var base = day(receipts.get(1), "2026-10-09");
        var target = day(receipts.get(2), "2026-10-12");
        var basePrice = new BigDecimal(f.path("base_price").asText());
        require(basePrice.compareTo(price(base, "close")) == 0, "供应商基准收盘发生修订，不能静默替换");
        var quote = receipts.get(0).path("response");
        require(quote.path("symbol").asText().equals("XAU/USD") && quote.path("datetime").asText().equals(date.toString()), "报价确认日期或标的不符");
        for (String field : List.of("open", "high", "low", "close")) {
            require(price(quote, field).compareTo(price(target, field)) == 0, "闭市报价与目标日线不符");
        }
        var hr = receipts.get(3);
        require(hr.path("request").path("timezone").asText().equals("UTC"), "小时请求不是UTC");
        var hroot = hr.path("response");
        require(hroot.path("status").asText().equals("ok")
                && hroot.path("meta").path("symbol").asText().equals("XAU/USD")
                && hroot.path("meta").path("interval").asText().equals("1h"), "小时线合同不符");
        var hours = new ArrayList<GoldHourBar>();
        for (var row : hroot.path("values")) {
            var start = LocalDateTime.parse(row.path("datetime").asText(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
            hours.add(new GoldHourBar(start, price(row, "open"), price(row, "high"), price(row, "low"), price(row, "close")));
        }
        var received = OffsetDateTime.parse(receipts.get(2).path("completedAt").asText());
        var checked = OffsetDateTime.parse(hr.path("completedAt").asText()).toInstant();
        var bar = new GoldDailyBar("XAUUSD", date, price(target, "open"), price(target, "high"),
                price(target, "low"), price(target, "close"), "usd", "troy_ounce", "twelve_data", received);
        var check = new GoldSessionCheck().check(bar, hours, checked);
        require(check.status() == GoldSessionCheckResult.Status.MATCHED, "完整时段核验未通过：" + check.status());
        var change = bar.close().subtract(basePrice).multiply(new BigDecimal("100"))
                .divide(basePrice, 12, RoundingMode.HALF_UP);
        String actual = new GoldForecastRule().classify(change).name().toLowerCase(Locale.ROOT);
        var result = new LinkedHashMap<String, Object>();
        result.put("researchOnly", true);
        result.put("officialSessionCertified", false);
        result.put("forecastId", f.path("id").asText());
        result.put("originalStatus", f.path("status").asText());
        result.put("publishedAt", f.path("created_at").asText());
        result.put("sessionCheck", check);
        result.put("sourceSha256", sha(Files.readAllBytes(Path.of(args[0]))));
        result.put("basePriceUnchanged", true);
        result.put("eodQuoteMatches", true);
        result.put("returnPercent", change);
        result.put("priceMovement", change.signum() < 0 ? "down" : "up");
        result.put("predicted", "neutral");
        result.put("actual", actual);
        result.put("hit", "neutral".equals(actual));
        result.put("fixedNeutralBaselineHit", "neutral".equals(actual));
        result.put("sampleCount", 1);
        result.put("accuracyImprovementProven", false);
        Files.writeString(Path.of(args[1]), json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(json.writeValueAsString(result));
    }
    private static JsonNode day(JsonNode receipt, String date) {
        var response = receipt.path("response");
        require(response.path("status").asText().equals("ok")
                && response.path("meta").path("symbol").asText().equals("XAU/USD")
                && response.path("meta").path("interval").asText().equals("1day")
                && response.path("values").size() == 1, "日线合同不符");
        var row = response.path("values").get(0);
        require(row.path("datetime").asText().equals(date), "日线日期不同");
        return row;
    }
    private static BigDecimal price(JsonNode row, String name) {
        require(row.path(name).isTextual(), "价格不是原始十进制字符串");
        return new BigDecimal(row.path(name).asText());
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
