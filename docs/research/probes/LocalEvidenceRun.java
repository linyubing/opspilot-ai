import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.forecast.GoldDirectionForecastContent;
import com.opspilot.ai.forecast.GoldEvidenceForecast;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.forecast.ConfiguredGoldTradingCalendar;
import com.opspilot.ai.marketdata.GoldSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 新日期一次性本地配对研究入口；保留失败，绝不写正式预测或可信准确率。 */
public class LocalEvidenceRun {
    private static final String MODEL = "qwen3.5:9b";
    private static final String DIGEST = "6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7";
    private static final String HOST = "http://127.0.0.1:11435";

    public static void main(String[] args) throws Exception {
        var calendar = calendar();
        if (args.length == 1 && "--window-check".equals(args[0])) {
            windowCase("weekday-before-has-no-window", "2026-10-05", "2026-10-06", "2026-10-05T20:00:00Z", false, false);
            windowCase("in-session-before-start-rejected", "2026-10-05", "2026-10-06", "2026-10-05T19:59:59Z", true, false);
            windowCase("in-session-start-accepted", "2026-10-05", "2026-10-06", "2026-10-05T20:00:00Z", true, true);
            windowCase("in-session-last-second-accepted", "2026-10-05", "2026-10-06", "2026-10-05T20:29:59Z", true, true);
            windowCase("in-session-deadline-rejected", "2026-10-05", "2026-10-06", "2026-10-05T20:30:00Z", true, false);
            windowCase("in-session-after-deadline-rejected", "2026-10-05", "2026-10-06", "2026-10-05T21:00:00Z", true, false);
            windowCase("weekend-before-accepted", "2026-10-09", "2026-10-12", "2026-10-10T00:00:00Z", false, true);
            windowCase("weekend-unclosed-base-rejected", "2026-10-09", "2026-10-12", "2026-10-09T19:59:59Z", false, false);
            return;
        }
        if (args.length == 1 && "--calendar-check".equals(args[0])) {
            for (LocalDate date = LocalDate.of(2026, 10, 5); !date.isAfter(LocalDate.of(2026, 10, 16)); date = date.plusDays(1)) {
                if (date.getDayOfWeek().getValue() > 5) continue;
                var next = calendar.nextBusinessDay(date);
                long seconds = Duration.between(GoldSession.forDate(date).end(), GoldSession.forDate(next).start()).getSeconds();
                System.out.println(date + " -> " + next + "; candidateWindowSeconds=" + seconds + "; officialCertified=false");
            }
            return;
        }
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !"--in-session".equals(args[3]))) {
            throw new IllegalArgumentException("请提供冻结输入、结果目录和目标标签日期，可选--in-session研究模式");
        }
        boolean inSession = args.length == 4;
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String frozen = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        var input = json.readTree(frozen);
        var record = json.treeToValue(input.path("snapshot"), StoredGoldResearchSnapshot.class);
        var snapshot = record.snapshot();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        // 日期边界固定，不因缺少新样本而放宽到已研究过的日期；检查在任何联网之前。
        check(snapshot.latestGoldDate() != null && snapshot.latestGoldDate().isAfter(LocalDate.of(2026, 10, 2)), "NO_NEW_BASE_DATE");
        check(snapshot.input() != null && snapshot.input().matches(snapshot.latestGoldDate(), snapshot.gold(), now), "GOLD_INPUT_INVALID");
        check(record.createdAt() != null && !record.createdAt().isAfter(now), "SNAPSHOT_TIME_INVALID");
        check(OffsetDateTime.parse(input.path("exportedAt").asText()).isBefore(now), "EXPORT_TIME_INVALID");
        if (snapshot.realRate() != null) check(snapshot.realRate().collectedAt() != null
                && !snapshot.realRate().collectedAt().isAfter(now), "RATE_TIME_INVALID");
        if (snapshot.dollarIndex() != null) check(snapshot.dollarIndex().collectedAt() != null
                && !snapshot.dollarIndex().collectedAt().isAfter(now), "DOLLAR_TIME_INVALID");
        LocalDate target = LocalDate.parse(args[2]);
        check(target.equals(calendar.nextBusinessDay(snapshot.latestGoldDate())), "TARGET_DATE_INVALID");
        var targetSession = GoldSession.forDate(target);
        var deadline = window(snapshot.latestGoldDate(), target, now, inSession);
        check("gold-direction-forecast-prompt-v2".equals(input.path("baseline").path("version").asText())
                && "gold-evidence-candidate-v1".equals(input.path("candidate").path("version").asText()), "PROMPT_VERSION_INVALID");
        for (String name : List.of("baseline", "candidate")) check(hash(input.path(name).path("content").asText())
                .equals(input.path(name).path("sha256").asText()), "PROMPT_HASH_INVALID");

        var directory = Path.of(args[1]).toAbsolutePath().normalize();
        check(Files.isDirectory(directory), "RESULT_DIRECTORY_REQUIRED");
        var phase = inSession ? "IN_SESSION_30_MIN" : "BEFORE_SESSION";
        var output = directory.resolve(snapshot.latestGoldDate() + "-" + phase + "-evidence-pair.json");
        var result = new LinkedHashMap<String, Object>();
        result.put("protocol", inSession ? "local-evidence-in-session-v1" : "local-evidence-pair-v1");
        result.put("publicationLayer", phase);
        result.put("researchOnly", true);
        result.put("trustedAccuracyEligible", false);
        result.put("officialSessionCertified", false);
        result.put("inputSha256", hash(frozen.replace("\r\n", "\n")));
        result.put("snapshotId", record.id());
        result.put("baseDate", snapshot.latestGoldDate());
        result.put("targetDate", target);
        result.put("candidateSessionStart", targetSession.start());
        result.put("publicationDeadline", deadline);
        result.put("calendarSource", "classpath-application-yaml-not-runtime-overrides");
        result.put("status", "STARTED_DO_NOT_RETRY");
        var outputs = new ArrayList<Map<String, Object>>();
        result.put("outputs", outputs);
        // 同目录同日期只允许一次；中断残留仍保留，不自动重启或覆盖。
        Files.writeString(output, json.writeValueAsString(result), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        try {
            checkDigest(client, json);
            for (String variant : List.of("baseline", "candidate")) {
                check(OffsetDateTime.now(ZoneOffset.UTC).toInstant().isBefore(deadline), "REQUEST_TOO_LATE");
                var prompt = input.path(variant);
                var request = Map.of("model", MODEL, "think", false, "stream", false, "keep_alive", "5m",
                        "messages", List.of(Map.of("role", "user", "content", prompt.path("content").asText())),
                        "options", Map.of("temperature", 0, "seed", 42, "num_ctx", 8192, "num_predict", 2048));
                var entry = new LinkedHashMap<String, Object>();
                outputs.add(entry);
                entry.put("variant", variant);
                entry.put("request", request);
                entry.put("promptSha256", prompt.path("sha256").asText());
                entry.put("startedAt", OffsetDateTime.now(ZoneOffset.UTC));
                save(output, json, result);
                var reply = client.send(HttpRequest.newBuilder(URI.create(HOST + "/api/chat"))
                        .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request), StandardCharsets.UTF_8))
                        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                var ended = OffsetDateTime.now(ZoneOffset.UTC);
                entry.put("completedAt", ended);
                entry.put("httpStatus", reply.statusCode());
                entry.put("rawResponse", reply.body());
                entry.put("rawResponseHash", hash(reply.body()));
                save(output, json, result); // 先保存原响应，解析或事实校验失败也不会丢失证据。
                check(ended.toInstant().isBefore(deadline), "RESPONSE_TOO_LATE");
                var body = json.readTree(reply.body());
                check(reply.statusCode() == 200 && body.path("done").asBoolean()
                        && "stop".equals(body.path("done_reason").asText())
                        && MODEL.equals(body.path("model").asText()), "RESPONSE_INCOMPLETE");
                var content = json.readTree(body.path("message").path("content").asText());
                try {
                    var validator = new GoldForecastValidator();
                    if ("candidate".equals(variant)) {
                        validator.validateEvidence(snapshot, json.treeToValue(content, GoldEvidenceForecast.class));
                        entry.put("structuredCitationsPassed", true);
                    } else validator.validate(json.treeToValue(content, GoldDirectionForecastContent.class));
                    entry.put("validationPassed", true);
                } catch (RuntimeException invalid) {
                    entry.put("validationPassed", false);
                    entry.put("validationFailure", invalid.getClass().getSimpleName());
                    // 保留被拒绝的原响应，不自动改正或重新生成。
                }
                entry.put("freeTextCertified", false);
                save(output, json, result);
            }
            checkDigest(client, json);
            result.put("status", "PAIRED_OUTPUTS_NOT_SCORED");
        } catch (Exception failure) {
            result.put("status", "FAILED_DO_NOT_RETRY");
            result.put("failureType", failure.getClass().getSimpleName());
            save(output, json, result);
            throw new IllegalStateException("研究配对失败，原始证据已保留；不要覆盖或自动重试", failure);
        }
        save(output, json, result);
        System.out.println("PAIRED_OUTPUTS_NOT_SCORED; FREE_TEXT_NOT_CERTIFIED; TRUSTED_ACCURACY_INELIGIBLE");
    }

    private static void checkDigest(HttpClient client, ObjectMapper json) throws Exception {
        var tags = client.send(HttpRequest.newBuilder(URI.create(HOST + "/api/tags"))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(tags.statusCode() == 200, "MODEL_TAGS_UNAVAILABLE");
        for (var model : json.readTree(tags.body()).path("models")) {
            if (MODEL.equals(model.path("name").asText()) && DIGEST.equals(model.path("digest").asText())) return;
        }
        throw new IllegalArgumentException("MODEL_DIGEST_CHANGED");
    }

    private static java.time.Instant window(LocalDate base, LocalDate target, OffsetDateTime now, boolean inSession) {
        var start = GoldSession.forDate(target).start();
        var baseEnd = GoldSession.forDate(base).end();
        // 独立研究层固定候选开始后30分钟，不把已经发生的大半天走势称作开盘前预测。
        var deadline = inSession ? start.plusSeconds(1800) : start;
        if (inSession) check(!now.toInstant().isBefore(start), "TARGET_SESSION_NOT_STARTED");
        else check(baseEnd.isBefore(deadline), "NO_PRE_SESSION_WINDOW");
        check(!now.toInstant().isBefore(baseEnd), "BASE_SESSION_NOT_CLOSED");
        check(now.toInstant().isBefore(deadline), "TOO_LATE_FOR_CANDIDATE_SESSION");
        return deadline;
    }

    // 研究工具的离线边界检查：仅使用日期与时刻，不生成行情或模型回执。
    private static void windowCase(String name, String base, String target, String time, boolean mode, boolean expected) {
        boolean accepted;
        try {
            window(LocalDate.parse(base), LocalDate.parse(target), OffsetDateTime.parse(time), mode);
            accepted = true;
        } catch (IllegalArgumentException rejected) {
            accepted = false;
        }
        if (accepted != expected) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }

    // 不启动应用或调度器；只复用编译资源的基础休市配置，不能冒充官方时段认证。
    private static ConfiguredGoldTradingCalendar calendar() throws Exception {
        var source = new org.springframework.boot.env.YamlPropertySourceLoader().load("research-calendar",
                new org.springframework.core.io.ClassPathResource("application.yaml")).getFirst();
        var holidays = new ArrayList<String>();
        for (int i = 0; ; i++) {
            var value = source.getProperty("opspilot.forecast.gold.holidays[" + i + "]");
            if (value == null) break;
            holidays.add(value.toString());
        }
        var calendar = new ConfiguredGoldTradingCalendar();
        calendar.setHolidays(new java.util.HashSet<>(holidays));
        return calendar;
    }

    private static void save(Path path, ObjectMapper json, Map<String, Object> result) throws Exception {
        Files.writeString(path, json.writeValueAsString(result), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
