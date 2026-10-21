package com.opspilot.ai.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 单次核验现存预测冻结基准；数据库只读、固定窗口、可按归档摘要离线复算。 */
public class CheckGoldStoredSession {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final UUID FORECAST = UUID.fromString("978b5294-fd31-4ff1-ad40-a7e422ee0ff6");

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--replay")) {
            Path file = Path.of(args[1]);
            byte[] bytes = Files.readAllBytes(file);
            require(sha(bytes).equals(args[2]), "核验归档摘要不符");
            var root = JSON.readTree(bytes);
            require(root.path("forecastId").asText().equals(FORECAST.toString()), "归档预测不符");
            require(root.path("checkerClassSha256").asText().equals(classHash(GoldSessionCheck.class)), "核验器字节码版本不符");
            var saved = JSON.treeToValue(root.path("result"), GoldSessionCheckResult.class);
            require(root.path("ruleVersion").asText().equals(GoldSessionCheckResult.RULE_VERSION), "时段规则版本不符");
            var input = saved.input();
            require(input != null && input.day().priceDate().equals(LocalDate.parse("2026-10-20")), "缺少原基准输入");
            var replayed = new GoldSessionCheck().check(input.day(), input.hours(), saved.checkedAt());
            require(replayed.status() == saved.status() && replayed.session().equals(saved.session())
                    && replayed.expectedHours() == saved.expectedHours() && replayed.receivedRows() == saved.receivedRows()
                    && replayed.missingHours().equals(saved.missingHours()), "离线复算与归档结果不一致");
            print(saved, sha(bytes), 0);
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("参数：新归档路径；或 --replay 归档路径 SHA256");
        Path output = Path.of(args[0]);
        require(!Files.exists(output), "归档已存在，拒绝覆盖或重复外部调用");
        GoldDailyBar day = storedBase();
        var properties = new TwelveDataProperties(URI.create("https://api.twelvedata.com"),
                System.getenv("TWELVE_DATA_API_KEY"), Duration.ofSeconds(5), Duration.ofSeconds(30));
        var service = new GoldSessionCheckService(new MarketDataConfiguration().twelveDataRestClient(properties),
                properties, Clock.systemUTC(), new GoldSessionCheck());
        var result = service.check(day);
        var archive = new LinkedHashMap<String, Object>();
        archive.put("forecastId", FORECAST.toString());
        archive.put("ruleVersion", GoldSessionCheckResult.RULE_VERSION);
        archive.put("checkerClassSha256", classHash(GoldSessionCheck.class));
        archive.put("result", result);
        archive.put("scope", "解析输入留档，不是原始HTTP认证、PIT证明或准确率实验");
        byte[] bytes = JSON.writeValueAsString(archive).getBytes(StandardCharsets.UTF_8);
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
        print(result, sha(bytes), 1);
    }

    private static GoldDailyBar storedBase() throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setURL("jdbc:postgresql://localhost:5432/opspilot_ai"); ds.setUser("postgres");
        ds.setPassword(System.getenv("OPSPILOT_DB_PASSWORD"));
        try (var connection = ds.getConnection()) {
            connection.setAutoCommit(false); connection.setReadOnly(true);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var row = jdbc.queryForMap("select base_date, base_price from gold_direction_forecast where id = ?", FORECAST);
            LocalDate date = ((java.sql.Date) row.get("base_date")).toLocalDate();
            require(date.equals(LocalDate.parse("2026-10-20")), "冻结基准日期已改变");
            var price = (java.math.BigDecimal) row.get("base_price");
            require(price.compareTo(new java.math.BigDecimal("4120.79556000")) == 0, "冻结基准价已改变");
            var bars = new JdbcGoldDailyBarRepository(jdbc).findRecent("XAUUSD", "twelve_data", date, 1);
            require(bars.size() == 1 && bars.getFirst().priceDate().equals(date), "仓库缺少同日基准");
            GoldDailyBar day = bars.getFirst();
            require(day.close().compareTo(price) == 0 && day.isConfirmedAt(OffsetDateTime.now(ZoneOffset.UTC)), "仓库基准与冻结价或确认依据不符");
            require(GoldSession.forDate(date).start().equals(Instant.parse("2026-10-19T20:00:00Z"))
                    && GoldSession.forDate(date).end().equals(Instant.parse("2026-10-20T20:00:00Z")), "候选窗口已改变");
            connection.rollback();
            return day;
        }
        // 连接在外部HTTP前关闭，不持有跨网络的数据库事务。
    }

    private static void print(GoldSessionCheckResult result, String hash, int calls) throws Exception {
        System.out.println(JSON.writeValueAsString(Map.of("forecastId", FORECAST, "date", result.session().date(),
                "status", result.status(), "expectedHours", result.expectedHours(), "receivedRows", result.receivedRows(),
                "archiveSha256", hash, "externalCalls", calls, "databaseWrites", 0, "scoresChanged", false)));
    }
    private static String classHash(Class<?> type) throws Exception {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            require(input != null, "缺少核验器字节码"); return sha(input.readAllBytes());
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
