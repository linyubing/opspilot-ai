import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 仅核对本地SDK响应模型标识；无行情输入、不连接数据库、不代表预测实验。 */
public class CheckGoldResponseModel {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("参数：本地Ollama地址 模型名");
        URI uri = URI.create(args[0]);
        if (!"http".equals(uri.getScheme()) || !java.util.Set.of("127.0.0.1", "localhost").contains(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("仅允许无凭据的本机Ollama地址");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(60000);
        var api = OllamaApi.builder().baseUrl(uri.toString())
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var model = OllamaChatModel.builder().ollamaApi(api).defaultOptions(OllamaChatOptions.builder()
                .model(args[1]).numCtx(8192).numPredict(2048).temperature(0.0).build()).build();
        var builder = ChatClient.builder(model).defaultOptions(OllamaChatOptions.builder().disableThinking().build());
        var gateway = new SpringAiGoldForecastGateway(builder, new ObjectMapper(), new GoldForecastProperties(args[1]));
        String input = """
                这是接口身份核验，不是行情预测。没有提供任何行情，禁止补造行情或新闻。
                只返回JSON：{"signal":"ABSTAINED","direction":null,"reasoning":"接口身份核验，无行情输入", "invalidationConditions":["真实研究另行执行"]}
                """;
        var prompt = new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION, input,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))));
        var result = gateway.generate(prompt);
        new GoldForecastValidator().validateFormal(result.content());
        System.out.println(new ObjectMapper().writeValueAsString(Map.of("requestedModel", args[1],
                "reportedModel", result.reportedModel(), "signal", result.content().signal(),
                "scope", "SDK身份核验；无行情、无数据库、非准确率实验、非工件认证")));
    }
}
