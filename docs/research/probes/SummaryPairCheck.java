import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.analysis.history.StoredGoldResearchSnapshot;
import com.opspilot.ai.forecast.GoldEvidenceForecast;
import com.opspilot.ai.forecast.GoldForecastValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 用正式校验器核对摘要消融研究的数字引用，不认证解释或方向准确率。 */
public class SummaryPairCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("请提供冻结输入及实验结果");
        var json = new ObjectMapper().findAndRegisterModules();
        var input = json.readTree(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        var result = json.readTree(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
        var snapshot = json.treeToValue(input.path("snapshot"), StoredGoldResearchSnapshot.class).snapshot();
        for (var entry : result.path("outputs")) {
            if (!entry.path("syntaxPassed").asBoolean()) {
                throw new IllegalArgumentException("原始输出语法失败，保留记录，禁止补写");
            }
            new GoldForecastValidator().validateEvidence(snapshot,
                    json.treeToValue(entry.path("content"), GoldEvidenceForecast.class));
            System.out.println(entry.path("variant").asText() + ": CITATIONS_PASSED; FREE_TEXT_NOT_CERTIFIED; NOT_SCORED");
        }
        if (result.path("outputs").size() != 2) throw new IllegalArgumentException("配对不完整");
    }
}
