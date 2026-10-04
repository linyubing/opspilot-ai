import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.GoldDirectionForecastContent;
import com.opspilot.ai.forecast.GoldForecastValidator;
import com.opspilot.ai.forecast.GoldEvidenceForecast;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 核对宏观诊断原始回执与已有安全校验；不认证事实或预测成绩。 */
public class LocalMacroCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("请提供冻结输入与诊断回执路径");
        var json = new ObjectMapper().findAndRegisterModules();
        var input = json.readTree(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        var result = json.readTree(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        String prompt = input.path("candidate").path("content").asText();
        check(hash(prompt).equals(input.path("candidate").path("sha256").asText()), "INPUT_HASH");
        check(prompt.equals(result.path("request").path("messages").get(0).path("content").asText()), "REQUEST_INPUT");
        check(input.path("candidate").path("sha256").asText().equals(result.path("candidateHash").asText()), "CANDIDATE_HASH");
        String raw = result.path("rawResponse").asText();
        check(hash(raw).equals(result.path("rawResponseHash").asText()), "RAW_RESPONSE_HASH");
        var response = json.readTree(raw);
        check(response.equals(result.path("response")), "PARSED_RESPONSE");
        check(result.path("httpStatus").asInt() == 200 && response.path("done").asBoolean()
                && "stop".equals(response.path("done_reason").asText()), "COMPLETE_RESPONSE");
        check(result.path("researchOnly").asBoolean() && !result.path("trustedAccuracyEligible").asBoolean(), "RESEARCH_ONLY");
        var body = json.readTree(response.path("message").path("content").asText());
        var validator = new GoldForecastValidator();
        if ("gold-evidence-candidate-v1".equals(input.path("candidate").path("version").asText())) {
            var record = json.treeToValue(input.path("snapshot"), StoredGoldResearchSnapshot.class);
            validator.validateEvidence(record.snapshot(), json.treeToValue(body, GoldEvidenceForecast.class));
            System.out.println("STRUCTURED_CITATIONS_PASSED; FREE_TEXT_NOT_CERTIFIED");
        } else {
            validator.validate(json.treeToValue(body, GoldDirectionForecastContent.class));
        }
        System.out.println("ARTIFACT_VALID; EXISTING_SAFETY_PASSED; FACTS_NOT_CERTIFIED; NOT_SCORED");
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean valid, String reason) {
        if (!valid) throw new IllegalArgumentException(reason);
    }
}
