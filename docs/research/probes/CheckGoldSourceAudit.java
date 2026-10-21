import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.GoldForecastRule;
import com.opspilot.ai.marketdata.JdbcGoldDailyBarRepository;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 只读检查来源版本差异及一个零点时段假设；不是模型评分或官方会话认证。 */
public class CheckGoldSourceAudit {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("需要旧日线回执与冻结配对回执");
        var result = new LinkedHashMap<String, Object>();
        result.put("researchOnly", true);
        result.put("officialSessionCertified", false);
        result.put("externalMarketApiCalls", 0);
        result.put("databaseReadOnly", true);
        result.put("databaseWritten", false);
        result.put("scoresChanged", false);
        result.put("receiptDrift", drift(Path.of(args[0])));
        result.put("midnightHypothesis", midnight(Path.of(args[1])));
        System.out.println(JSON.writeValueAsString(result));
    }

    private static Map<String, Object> drift(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        require(sha(bytes).equals("090e5de5fc1ceb49fa1e5bddd21dfd62a6094a0cc8dfbc4c3a3e2424a45bf974"), "旧预测及来源归档已改变");
        var root = JSON.readTree(bytes);
        var receipt = root.path("receipts").get(2);
        String raw = receipt.path("rawResponse").asText();
        String oldHash = sha(raw.getBytes(StandardCharsets.UTF_8));
        require(oldHash.equals("3ca28f682c454a6168e48ebd2ad85c939559510e26f4862db93aacd221e367da"), "原目标回执已改变");
        var source = JSON.readTree(raw);
        require(source.path("meta").path("symbol").asText().equals("XAU/USD")
                && source.path("meta").path("interval").asText().equals("1day"), "原日线标识不符");
        var old = source.path("values").get(0);
        LocalDate day = LocalDate.parse(old.path("datetime").asText());
        require(day.equals(LocalDate.parse("2026-10-12")), "原目标日期不符");

        var ds = new PGSimpleDataSource();
        ds.setURL("jdbc:postgresql://localhost:5432/opspilot_ai");
        ds.setUser("postgres");
        ds.setPassword(System.getenv("OPSPILOT_DB_PASSWORD"));
        // 单连接只读事务，不能把另一个连接的SET命令误当作本次查询的保护。
        try (var connection = ds.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var repo = new JdbcGoldDailyBarRepository(jdbc);
            var stored = repo.findRecent("XAUUSD", "twelve_data", day, 1).getFirst();
            require(stored.priceDate().equals(day), "仓库没有相同日期");
            require("usd".equals(stored.currency()) && "troy_ounce".equals(stored.unit()), "仓库价格单位不同，不能比较");
            require(stored.collectedAt().isAfter(OffsetDateTime.parse(receipt.path("completedAt").asText())), "仓库采集版本并不晚于旧回执");
            var storedPrices = new LinkedHashMap<String, BigDecimal>();
            storedPrices.put("open", stored.open()); storedPrices.put("high", stored.high());
            storedPrices.put("low", stored.low()); storedPrices.put("close", stored.close());
            var matches = new LinkedHashMap<String, Boolean>();
            storedPrices.forEach((k, v) -> matches.put(k, v.compareTo(price(old, k)) == 0));
            BigDecimal base = new BigDecimal(root.path("forecast").path("base_price").asText());
            BigDecimal oldReturn = change(price(old, "close"), base);
            BigDecimal storedReturn = change(stored.close(), base);
            var values = new LinkedHashMap<String, Object>();
            values.put("date", day.toString());
            values.put("oldCollectedAt", receipt.path("completedAt").asText());
            values.put("warehouseCollectedAt", stored.collectedAt().toString());
            values.put("oldRawHash", oldHash); values.put("priceMatches", matches);
            values.put("closeDifferencePercent", change(stored.close(), price(old, "close")));
            values.put("originalReceiptReturnPercent", oldReturn);
            values.put("warehouseReturnPercent", storedReturn);
            values.put("originalReceiptClass", new GoldForecastRule().classify(oldReturn).name());
            values.put("warehouseClass", new GoldForecastRule().classify(storedReturn).name());
            values.put("note", "这是旧回执与当前仓库的版本差异，不是厂商已确认的修订原因，也不重写原评分");
            connection.rollback();
            return values;
        }
    }

    private static Map<String, Object> midnight(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        require(sha(bytes).equals("a78b45cc330438d43439cb14fcb11bf617b9d3ffe8349a36dc04105e9227632f"), "配对来源归档已改变");
        var receipts = JSON.readTree(bytes).path("receipts");
        var dayRow = JSON.readTree(receipts.get(1).path("rawResponse").asText()).path("values").get(0);
        LocalDate day = LocalDate.parse(dayRow.path("datetime").asText());
        require(day.equals(LocalDate.parse("2026-10-14")), "诊断日期不符");
        ZoneId zone = ZoneId.of("Australia/Sydney");
        Instant start = day.atStartOfDay(zone).toInstant(), end = day.plusDays(1).atStartOfDay(zone).toInstant();
        var times = new TreeMap<Instant, JsonNode>();
        for (var row : JSON.readTree(receipts.get(2).path("rawResponse").asText()).path("values")) {
            Instant time = LocalDateTime.parse(row.path("datetime").asText(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
            if (!time.isBefore(start) && time.isBefore(end)) require(times.put(time, row) == null, "小时重复");
        }
        for (Instant t = start; t.isBefore(end); t = t.plusSeconds(3600)) require(times.containsKey(t), "诊断窗口覆盖不足");
        var aggregate = new LinkedHashMap<String, BigDecimal>();
        aggregate.put("open", price(times.firstEntry().getValue(), "open"));
        aggregate.put("close", price(times.lastEntry().getValue(), "close"));
        aggregate.put("high", times.values().stream().map(r -> price(r, "high")).max(BigDecimal::compareTo).orElseThrow());
        aggregate.put("low", times.values().stream().map(r -> price(r, "low")).min(BigDecimal::compareTo).orElseThrow());
        var matches = new LinkedHashMap<String, Boolean>();
        aggregate.forEach((k, v) -> matches.put(k, v.compareTo(price(dayRow, k)) == 0));
        return Map.of("hypothesis", "sydney-midnight-diagnostic-only", "date", day.toString(),
                "start", start.toString(), "end", end.toString(), "hours", times.size(), "matches", matches,
                "note", "UTC仅由原请求指定；本地零点是单次诊断假设，不替换冻结07:00合同，不继续搜索偏移");
    }

    private static BigDecimal price(JsonNode row, String name) {
        require(row.path(name).isTextual(), "价格必须是原始十进制字符串");
        return new BigDecimal(row.path(name).asText());
    }

    private static BigDecimal change(BigDecimal current, BigDecimal base) {
        return current.subtract(base).divide(base, 12, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
