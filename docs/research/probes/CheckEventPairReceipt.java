import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.marketdata.GoldDailyBar;
import com.opspilot.ai.marketdata.GoldHourBar;
import com.opspilot.ai.marketdata.GoldSession;
import com.opspilot.ai.marketdata.GoldSessionCheck;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;

/** 使用生产时段核验器独立检查冻结研究回执；不联网、不评分、不改数据库。 */
public class CheckEventPairReceipt {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要冻结HTTP归档路径");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!hash.equals("a78b45cc330438d43439cb14fcb11bf617b9d3ffe8349a36dc04105e9227632f")) {
            throw new IllegalArgumentException("不是已冻结的真实HTTP归档");
        }
        var json = new ObjectMapper();
        var receipts = json.readTree(bytes).path("receipts");
        var hoursRoot = json.readTree(receipts.get(2).path("rawResponse").asText());
        var hours = new ArrayList<GoldHourBar>();
        for (var row : hoursRoot.path("values")) {
            var start = LocalDateTime.parse(row.path("datetime").asText(),
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC);
            hours.add(new GoldHourBar(start, new BigDecimal(row.path("open").asText()),
                    new BigDecimal(row.path("high").asText()), new BigDecimal(row.path("low").asText()),
                    new BigDecimal(row.path("close").asText())));
        }
        for (int i = 0; i < 2; i++) {
            var receipt = receipts.get(i);
            var row = json.readTree(receipt.path("rawResponse").asText()).path("values").get(0);
            LocalDate day = LocalDate.parse(row.path("datetime").asText());
            var session = GoldSession.forDate(day);
            var bars = hours.stream().filter(h -> !h.start().isBefore(session.start())
                    && h.start().isBefore(session.end())).toList();
            var bar = new GoldDailyBar("XAUUSD", day, new BigDecimal(row.path("open").asText()),
                    new BigDecimal(row.path("high").asText()), new BigDecimal(row.path("low").asText()),
                    new BigDecimal(row.path("close").asText()), "usd", "troy_ounce", "twelve_data",
                    OffsetDateTime.parse(receipt.path("completedAt").asText()));
            var check = new GoldSessionCheck().check(bar, bars,
                    OffsetDateTime.parse(receipts.get(2).path("completedAt").asText()).toInstant());
            System.out.println("date=" + day + ", hours=" + bars.size() + ", status=" + check.status());
            if (check.status() != com.opspilot.ai.marketdata.GoldSessionCheckResult.Status.OHLC_MISMATCH) {
                throw new IllegalStateException("生产核验结果与冻结研究诊断不一致");
            }
        }
    }
}
