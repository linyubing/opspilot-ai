import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.forecast.GoldDirectionForecastContent;
import com.opspilot.ai.forecast.GoldForecastPromptBuilder;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.marketdata.GoldSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/** 离线核对真实配对回执、输入窗口及现有安全校验；不调用模型或数据库，不认证预测正确。 */
public class LocalPromptPairCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("INPUT_AND_RESULT_PATH_REQUIRED");
        var json = new ObjectMapper().findAndRegisterModules();
        var inputFile = Path.of(args[0]);
        var pair = json.readTree(inputFile.toFile());
        var result = json.readTree(Path.of(args[1]).toFile());
        var inputHash = hash(Files.readAllBytes(inputFile));
        check(inputHash.equals(result.path("inputSha256").asText()) && inputHash.equals(
                "493567c81d051d9e1818fa3d47a00f77e3b15e2dff2622c4acdcd38d1a67407c"), "INPUT_HASH");
        check(hash(Files.readAllBytes(Path.of(result.path("protocol").asText())))
                .equals(result.path("protocolSha256").asText()), "PROTOCOL_HASH");
        check(result.path("researchOnly").asBoolean()
                && !result.path("trustedAccuracyEligible").asBoolean()
                && !result.path("officialSessionCertified").asBoolean(), "RESEARCH_BOUNDARY");
        check(result.path("brierScore").isNull() && result.path("logLoss").isNull(), "NO_FAKE_PROBABILITY");
        check(result.path("modelUnchanged").asBoolean(), "MODEL_CHANGED");
        check(result.path("modelDigest").asText().equals(
                "6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7"), "MODEL_DIGEST");
        check(result.path("status").asText().equals("PAIRED_OUTPUTS_NOT_SCORED"), "PAIR_STATUS");
        var record = json.treeToValue(pair.path("snapshot"), StoredGoldResearchSnapshot.class);
        check(record.id().toString().equals(result.path("snapshotId").asText()), "SNAPSHOT_ID");
        check(record.snapshot().latestGoldDate().toString().equals(result.path("baseDate").asText())
                && result.path("targetDate").asText().equals("2026-10-05"), "FROZEN_DATES");
        check(record.snapshot().gold().currentPrice().compareTo(result.path("basePrice").decimalValue()) == 0,
                "BASE_PRICE");
        var deadline = GoldSession.forDate(LocalDate.parse(result.path("targetDate").asText())).start();
        check(deadline.equals(Instant.parse(result.path("candidateSessionStart").asText())), "CANDIDATE_SESSION");
        var outputs = result.path("outputs");
        check(outputs.isArray() && outputs.size() == 2, "PAIR_SIZE");
        Instant last = null;
        String direction = null;
        boolean sameDirection = true;
        for (int i = 0; i < 2; i++) {
            var name = i == 0 ? "baseline" : "candidate";
            var output = outputs.get(i);
            check(name.equals(output.path("variant").asText()), "PAIR_ORDER");
            // 冻结 JSON 的数值未保留数据库尾零；核对真正发出的完整文本，不重建改变格式。
            var prompt = pair.path(name);
            var textHash = hash(prompt.path("content").asText().getBytes(StandardCharsets.UTF_8));
            check(textHash.equals(prompt.path("sha256").asText())
                    && textHash.equals(output.path("promptSha256").asText())
                    && prompt.path("version").asText().equals(output.path("promptVersion").asText())
                    && prompt.path("version").asText().equals(i == 0
                        ? GoldForecastPromptBuilder.PROMPT_VERSION : GoldForecastPromptBuilder.CANDIDATE_VERSION),
                    "PROMPT_BINDING");
            var request = output.path("request");
            check(request.path("messages").size() == 1
                    && request.path("messages").get(0).path("role").asText().equals("user")
                    && request.path("messages").get(0).path("content").asText().equals(prompt.path("content").asText()),
                    "REQUEST_INPUT");
            check(request.path("model").asText().equals("qwen3.5:9b")
                    && !request.path("think").asBoolean(true)
                    && !request.path("stream").asBoolean(true)
                    && request.path("keep_alive").asText().equals("5m") && !request.has("format"),
                    "REQUEST_MODE");
            var options = request.path("options");
            check(options.path("temperature").asDouble(-1) == 0
                    && options.path("seed").asInt(-1) == 42
                    && options.path("num_ctx").asInt() == 8192
                    && options.path("num_predict").asInt() == 2048, "REQUEST_OPTIONS");
            var started = OffsetDateTime.parse(output.path("startedAt").asText());
            var ended = OffsetDateTime.parse(output.path("completedAt").asText());
            check(!started.isAfter(ended) && ended.toInstant().isBefore(deadline)
                    && (last == null || !started.toInstant().isBefore(last)), "REQUEST_TIME");
            check(record.snapshot().input() != null && record.snapshot().input()
                    .matches(record.snapshot().latestGoldDate(), record.snapshot().gold(), started), "GOLD_WINDOW");
            last = ended.toInstant();
            var raw = output.path("rawResponse").asText();
            check(hash(raw.getBytes(StandardCharsets.UTF_8)).equals(output.path("responseSha256").asText()),
                    "RAW_RESPONSE_HASH");
            var response = json.readTree(raw);
            check(response.path("model").asText().equals("qwen3.5:9b")
                    && response.path("done").asBoolean() && response.path("done_reason").asText().equals("stop")
                    && output.path("http").asInt() == 200, "RESPONSE_COMPLETE");
            var created = Instant.parse(response.path("created_at").asText());
            check(!created.isBefore(started.toInstant()) && !created.isAfter(ended.toInstant()), "RESPONSE_TIME");
            var text = response.path("message").path("content").asText();
            check(hash(text.getBytes(StandardCharsets.UTF_8)).equals(output.path("contentSha256").asText()),
                    "CONTENT_HASH");
            var content = json.readValue(text, GoldDirectionForecastContent.class);
            new GoldForecastValidator().validate(content);
            check(json.valueToTree(content).equals(output.path("parsed")), "PARSED_BINDING");
            check(output.path("protocolPass").asBoolean() && output.path("structuralPass").asBoolean()
                    && output.path("completedNormally").asBoolean()
                    && output.path("beforeCandidateSession").asBoolean(), "SUMMARY_FLAGS");
            if (direction == null) direction = content.direction().name();
            else sameDirection = direction.equals(content.direction().name());
        }
        // 通过只证明归档一致、现有安全校验通过，不证明事实依据和预测效果。
        System.out.println("{\"status\":\"PAIR_ARTIFACT_VALID\",\"sameDirection\":" + sameDirection
                + ",\"existingSafetyPassed\":true,\"factsCertified\":false,\"trustedAccuracyEligible\":false}");
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void check(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
