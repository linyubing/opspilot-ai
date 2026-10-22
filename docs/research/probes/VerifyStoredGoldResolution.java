import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 离线复算本轮已存结算；只读取 API 回读文件，不证明原生来源、官方时段或历史可得性。 */
public final class VerifyStoredGoldResolution {
    private static final String FORECAST_ID = "978b5294-fd31-4ff1-ad40-a7e422ee0ff6";
    private static final String BASE_DATE = "2026-10-20";
    private static final String TARGET_DATE = "2026-10-21";
    private static final String DIRECTION_RULE = "gold-next-session-direction-v1";
    private static final String SESSION_RULE = "sydney-0700-candidate-v1";
    private static final Instant BASE_START = Instant.parse("2026-10-19T20:00:00Z");
    private static final Instant BASE_END = Instant.parse("2026-10-20T20:00:00Z");
    private static final Instant TARGET_START = BASE_END;
    private static final Instant TARGET_END = Instant.parse("2026-10-21T20:00:00Z");
    private static final BigDecimal HALF_PERCENT = new BigDecimal("0.500000");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private VerifyStoredGoldResolution() { }

    public static void main(String[] args) {
        try {
            Arguments options = arguments(args);
            byte[] bytes = Files.readAllBytes(options.input());
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            // 预期摘要必须由调用方另行提供；先比对首次捕获锚点，绝不由文件自填预期值。
            require(MessageDigest.isEqual(digest, HexFormat.of().parseHex(options.expectedSha256())),
                    "输入 SHA-256 与外部预期锚点不符");
            JsonNode root;
            try {
                root = JSON.readTree(bytes);
            } catch (Exception exception) {
                throw new Rejected("JSON 无效、重复字段或存在尾随内容");
            }
            Summary summary = verify(root);
            int negativeTests = options.selfTest() ? selfTest((ObjectNode) root) : 0;
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("result", "PASS");
            output.put("forecastId", FORECAST_ID);
            output.put("baseDate", BASE_DATE);
            output.put("targetDate", TARGET_DATE);
            output.put("status", "RESOLVED");
            output.put("signal", "ABSTAINED");
            output.put("actualReturn", summary.actualReturn());
            output.put("actualDirection", summary.actualDirection());
            output.put("hit", null);
            output.put("forecastsRead", summary.forecastsRead());
            output.put("checksRead", summary.checksRead());
            output.put("passedChecksVerified", summary.passedChecks());
            output.put("hourRowsVerified", summary.passedChecks() * 48);
            output.put("requestOnlyUtcProofs", summary.requestOnlyUtcProofs());
            output.put("responseUtcProofs", summary.responseUtcProofs());
            output.put("negativeSelfTestsRejected", negativeTests);
            output.put("directionBoundarySelfTests", options.selfTest() ? 4 : 0);
            output.put("inputSha256", HexFormat.of().formatHex(digest));
            output.put("externalCalls", 0);
            output.put("filesWritten", 0);
            output.put("scope", "API回读输入的离线一致性核验；不是原生HTTP、官方时段或PIT认证");
            System.out.println(JSON.writeValueAsString(output));
        } catch (Rejected exception) {
            System.err.println("FAIL: " + exception.getMessage());
            System.exit(1);
        } catch (Exception exception) {
            // 不转储解析异常或输入全文，避免把完整行情带入终端输出。
            System.err.println("FAIL: 无法读取输入或执行离线核验");
            System.exit(1);
        }
    }

    private static Arguments arguments(String[] args) {
        Path input = null;
        String hash = null;
        boolean selfTest = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> {
                    require(input == null && i + 1 < args.length, "--input 必须且只能提供一次");
                    input = Path.of(args[++i]);
                }
                case "--expected-sha256" -> {
                    require(hash == null && i + 1 < args.length, "--expected-sha256 必须且只能提供一次");
                    hash = args[++i];
                }
                case "--self-test" -> {
                    require(!selfTest, "--self-test 只能提供一次");
                    selfTest = true;
                }
                default -> throw new Rejected("参数：--input 文件 --expected-sha256 首次捕获摘要 [--self-test]");
            }
        }
        require(input != null && hash != null && hash.matches("[0-9a-fA-F]{64}"),
                "必须传入文件及独立保存的首次捕获 SHA-256，不能使用本次自算值充当外部锚点");
        return new Arguments(input, hash, selfTest);
    }

    private static Summary verify(JsonNode root) {
        object(root, "根节点");
        ArrayNode forecasts = array(root, "forecasts");
        ArrayNode checks = array(root, "checks");
        JsonNode forecast = forecast(root);
        equalText(forecast, "baseDate", BASE_DATE);
        equalText(forecast, "targetDate", TARGET_DATE);
        equalText(forecast, "expectedTargetDate", TARGET_DATE);
        equalText(forecast, "forecastRuleVersion", DIRECTION_RULE);
        equalText(forecast, "status", "RESOLVED");
        equalText(forecast, "signal", "ABSTAINED");
        explicitNull(forecast, "predictedDirection");
        explicitNull(forecast, "hit");

        JsonNode timing = object(forecast.get("timing"), "原目标时段");
        equalText(timing, "targetDate", TARGET_DATE);
        equalText(timing, "ruleVersion", SESSION_RULE);
        require(time(timing, "start").equals(TARGET_START)
                        && time(timing, "end").equals(TARGET_END), "原承诺目标窗口发生漂移");
        Instant createdAt = time(forecast, "createdAt");
        Instant resolvedAt = time(forecast, "resolvedAt");
        require(!createdAt.isBefore(BASE_END) && createdAt.isBefore(TARGET_END), "发布时间不符合原时段合同");
        require(!resolvedAt.isBefore(TARGET_END) && !resolvedAt.isBefore(createdAt), "结算早于原目标结束或发布时间");

        BigDecimal basePrice = number(forecast, "basePrice");
        BigDecimal targetPrice = number(forecast, "targetPrice");
        require(basePrice.signum() > 0 && targetPrice.signum() > 0, "记录原价必须为正");
        // 先乘百分数再直接除到六位，独立于生产实现和任何浮点中间值。
        BigDecimal actualReturn = targetPrice.subtract(basePrice).multiply(new BigDecimal("100"))
                .divide(basePrice, 6, RoundingMode.HALF_UP);
        require(actualReturn.compareTo(number(forecast, "actualReturn")) == 0, "独立六位收益与已存收益不一致");
        String direction = classify(actualReturn);
        equalText(forecast, "actualDirection", direction);

        int passed = 0;
        int requestOnly = 0;
        int responseUtc = 0;
        Set<String> ids = new HashSet<>();
        for (JsonNode check : checks) {
            object(check, "检查记录");
            equalText(check, "forecastId", FORECAST_ID);
            require(ids.add(text(check, "id")), "检查记录 id 重复");
            if (!text(check, "code").equals("CHECK_PASSED")) continue;
            Instant checkedAt = time(check, "checkedAt");
            require(!checkedAt.isBefore(TARGET_END) && !checkedAt.isAfter(resolvedAt),
                    "CHECK_PASSED 不在原目标结束至结算之间");
            boolean baseRequest = verifyEnd(check.get("baseCheck"), BASE_DATE,
                    BASE_START, BASE_END, basePrice, checkedAt, resolvedAt);
            boolean targetRequest = verifyEnd(check.get("targetCheck"), TARGET_DATE,
                    TARGET_START, TARGET_END, targetPrice, checkedAt, resolvedAt);
            requestOnly += (baseRequest ? 1 : 0) + (targetRequest ? 1 : 0);
            responseUtc += (baseRequest ? 0 : 1) + (targetRequest ? 0 : 1);
            passed++;
        }
        require(passed > 0, "缺少绑定该预测的 CHECK_PASSED");
        return new Summary(actualReturn, direction, forecasts.size(), checks.size(), passed, requestOnly, responseUtc);
    }

    private static boolean verifyEnd(JsonNode proof, String date, Instant start, Instant end,
                                     BigDecimal recordedPrice, Instant checkedAt, Instant resolvedAt) {
        object(proof, "两端核验凭据");
        equalText(proof, "status", "MATCHED");
        require(integer(proof, "expectedHours") == 24 && integer(proof, "receivedRows") == 24,
                "核验计数不是 24 小时");
        require(array(proof, "missingHours").isEmpty(), "凭据报告缺小时");
        Instant proofTime = time(proof, "checkedAt");
        require(!proofTime.isBefore(TARGET_END) && !proofTime.isAfter(checkedAt)
                        && !proofTime.isAfter(resolvedAt), "两端核验时间不在原目标结束至结算之间");
        JsonNode session = object(proof.get("session"), "两端时段");
        equalText(session, "date", date);
        require(time(session, "start").equals(start) && time(session, "end").equals(end),
                "两端日期或固定 UTC 窗口错误");
        JsonNode input = object(proof.get("input"), "两端原始输入");
        String basis = text(input, "timezoneBasis");
        require(basis.equals("REQUEST_ONLY_UTC") || basis.equals("RESPONSE_UTC"), "小时输入 UTC 依据未知");
        JsonNode day = object(input.get("day"), "原生日线");
        equalText(day, "symbol", "XAUUSD");
        equalText(day, "provider", "twelve_data");
        equalText(day, "currency", "usd");
        equalText(day, "unit", "troy_ounce");
        equalText(day, "priceDate", date);
        validPrices(day);
        require(number(day, "close").compareTo(recordedPrice) == 0, "日线原价未绑定记录 basePrice/targetPrice");
        Instant collectedAt = time(day, "collectedAt");
        require(!collectedAt.isBefore(end) && !collectedAt.isAfter(proofTime), "日线采集时间早于结束或晚于核验");
        verifyConfirmation(day, date, collectedAt, proofTime);

        // 不信任输入顺序或 MATCHED 标签：独立按时间排序，逐个检查完整整点网格。
        ArrayNode hours = array(input, "hours");
        require(hours.size() == 24, "小时输入必须恰有 24 行");
        TreeMap<Instant, JsonNode> ordered = new TreeMap<>();
        for (JsonNode hour : hours) {
            object(hour, "小时行");
            Instant hourStart = time(hour, "start");
            require(hourStart.getNano() == 0 && Math.floorMod(hourStart.getEpochSecond(), 3600) == 0,
                    "小时输入含非整点时间");
            require(!hourStart.isBefore(start) && hourStart.isBefore(end), "小时输入越过原窗口");
            require(ordered.putIfAbsent(hourStart, hour) == null, "小时输入有重复整点");
            validPrices(hour);
        }
        BigDecimal high = null;
        BigDecimal low = null;
        for (int i = 0; i < 24; i++) {
            JsonNode hour = ordered.get(start.plusSeconds(i * 3600L));
            require(hour != null, "小时输入缺少连续整点");
            BigDecimal hourHigh = number(hour, "high");
            BigDecimal hourLow = number(hour, "low");
            high = high == null ? hourHigh : high.max(hourHigh);
            low = low == null ? hourLow : low.min(hourLow);
        }
        require(number(ordered.firstEntry().getValue(), "open").compareTo(number(day, "open")) == 0
                        && high.compareTo(number(day, "high")) == 0
                        && low.compareTo(number(day, "low")) == 0
                        && number(ordered.lastEntry().getValue(), "close").compareTo(number(day, "close")) == 0,
                "独立小时聚合 OHLC 与原生日线不精确一致");
        return basis.equals("REQUEST_ONLY_UTC");
    }

    private static void verifyConfirmation(JsonNode day, String date, Instant collectedAt, Instant proofTime) {
        JsonNode confirmation = object(day.get("confirmation"), "日线闭市凭据");
        equalText(confirmation, "source", "twelve_data_quote_eod_v1");
        require(text(confirmation, "receiptHash").matches("[0-9a-f]{64}"), "日线闭市凭据摘要无效");
        LocalDate closed = date(confirmation, "closedDay");
        Instant confirmationTime = time(confirmation, "checkedAt");
        require(!closed.isBefore(LocalDate.parse(date))
                        && !closed.isAfter(confirmationTime.atOffset(ZoneOffset.UTC).toLocalDate())
                        && !confirmationTime.isBefore(collectedAt) && !confirmationTime.isAfter(proofTime),
                "日线闭市凭据日期或时间无效");
    }

    private static int selfTest(ObjectNode original) {
        int count = 0;
        count += rejects(original, root -> ((ArrayNode) passedCheck(root).at("/baseCheck/input/hours")).remove(0));
        count += rejects(original, root -> {
            ObjectNode day = (ObjectNode) passedCheck(root).at("/targetCheck/input/day");
            day.put("close", number(day, "close").add(new BigDecimal("0.01")));
        });
        count += rejects(original, root -> ((ObjectNode) forecast(root).get("timing")).put("targetDate", "2026-10-22"));
        count += rejects(original, root -> ((ObjectNode) forecast(root)).put("hit", true));
        count += rejects(original, root -> {
            ArrayNode hours = (ArrayNode) passedCheck(root).at("/targetCheck/input/hours");
            ((ObjectNode) hours.get(0)).put("start", text(hours.get(1), "start"));
        });
        count += rejects(original, root -> ((ObjectNode) passedCheck(root).get("targetCheck"))
                .put("checkedAt", TARGET_END.minusSeconds(1).toString()));
        count += rejects(original, root -> ((ObjectNode) passedCheck(root))
                .put("forecastId", "00000000-0000-0000-0000-000000000000"));
        count += rejects(original, root -> ((ObjectNode) passedCheck(root).at("/targetCheck/input"))
                .put("timezoneBasis", "UNKNOWN"));
        count += rejects(original, root -> {
            ObjectNode day = (ObjectNode) passedCheck(root).at("/baseCheck/input/day");
            day.put("high", number(day, "high").add(new BigDecimal("0.00000000000000000001")));
        });
        require(classify(new BigDecimal("0.500000")).equals("NEUTRAL")
                        && classify(new BigDecimal("-0.500000")).equals("NEUTRAL")
                        && classify(new BigDecimal("0.500001")).equals("BULLISH")
                        && classify(new BigDecimal("-0.500001")).equals("BEARISH"), "方向边界自测失败");
        return count;
    }

    private static int rejects(ObjectNode original, Consumer<ObjectNode> mutation) {
        ObjectNode copy = original.deepCopy();
        mutation.accept(copy);
        try {
            verify(copy);
        } catch (Rejected expected) {
            return 1;
        }
        throw new Rejected("内存篡改自测未拒绝无效输入");
    }

    private static JsonNode forecast(JsonNode root) {
        JsonNode result = null;
        for (JsonNode value : array(root, "forecasts")) {
            object(value, "预测记录");
            if (!text(value, "id").equals(FORECAST_ID)) continue;
            require(result == null, "目标预测 id 在输入中重复");
            result = value;
        }
        return object(result, "指定预测");
    }

    private static JsonNode passedCheck(JsonNode root) {
        for (JsonNode check : array(root, "checks")) {
            if (text(check, "code").equals("CHECK_PASSED")) return check;
        }
        throw new Rejected("缺少 CHECK_PASSED");
    }

    private static String classify(BigDecimal value) {
        if (value.compareTo(HALF_PERCENT) > 0) return "BULLISH";
        if (value.compareTo(HALF_PERCENT.negate()) < 0) return "BEARISH";
        return "NEUTRAL";
    }

    private static void validPrices(JsonNode value) {
        BigDecimal open = number(value, "open"), high = number(value, "high");
        BigDecimal low = number(value, "low"), close = number(value, "close");
        require(open.signum() > 0 && high.signum() > 0 && low.signum() > 0 && close.signum() > 0
                        && high.compareTo(low) >= 0 && high.compareTo(open) >= 0 && high.compareTo(close) >= 0
                        && low.compareTo(open) <= 0 && low.compareTo(close) <= 0, "OHLC 价格非正或高低区间不合理");
    }

    private static JsonNode object(JsonNode value, String name) {
        require(value != null && value.isObject(), name + "必须为对象");
        return value;
    }

    private static ArrayNode array(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        require(value != null && value.isArray(), name + "必须为数组");
        return (ArrayNode) value;
    }

    private static String text(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        require(value != null && value.isTextual() && !value.textValue().isBlank(), name + "必须为非空字符串");
        return value.textValue();
    }

    private static BigDecimal number(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        require(value != null && value.isNumber(), name + "必须为精确 JSON 数字");
        return value.decimalValue();
    }

    private static int integer(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), name + "必须为整数");
        return value.intValue();
    }

    private static Instant time(JsonNode parent, String name) {
        try {
            return Instant.parse(text(parent, name));
        } catch (Rejected expected) {
            throw expected;
        } catch (Exception exception) {
            throw new Rejected(name + "必须为带偏移量的 ISO 时间");
        }
    }

    private static LocalDate date(JsonNode parent, String name) {
        try {
            return LocalDate.parse(text(parent, name));
        } catch (Rejected expected) {
            throw expected;
        } catch (Exception exception) {
            throw new Rejected(name + "必须为 ISO 日期");
        }
    }

    private static void explicitNull(JsonNode parent, String name) {
        require(parent.has(name) && parent.get(name).isNull(), name + "必须显式为 null");
    }

    private static void equalText(JsonNode parent, String name, String expected) {
        require(text(parent, name).equals(expected), name + "与固定核验合同不符");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new Rejected(message);
    }

    private record Arguments(Path input, String expectedSha256, boolean selfTest) { }
    private record Summary(BigDecimal actualReturn, String actualDirection, int forecastsRead, int checksRead,
                           int passedChecks, int requestOnlyUtcProofs, int responseUtcProofs) { }
    private static final class Rejected extends RuntimeException {
        private Rejected(String message) { super(message); }
    }
}
