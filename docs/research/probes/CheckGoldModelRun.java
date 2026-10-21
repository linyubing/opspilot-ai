import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opspilot.ai.config.LocalAiConfiguration;
import com.opspilot.ai.forecast.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 单次无行情身份探针：读取真实配置并捕获SDK出站请求，不连接数据库。 */
public class CheckGoldModelRun {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INPUT = "这是接口身份核验，不是行情预测。没有行情，禁止补造。只返回JSON："
            + "{\"signal\":\"ABSTAINED\",\"direction\":null,\"reasoning\":\"无行情输入，仅核验身份\","
            + "\"invalidationConditions\":[\"真实研究另行执行\"]}";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("参数：固定协议路径 新归档路径");
        Path protocolPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        require(!Files.exists(output), "归档已存在，拒绝覆盖或重复调用");
        // 同路径尝试只允许一次；失败也保留锁，不自动重发。不同路径不构成全局限次。
        Files.writeString(output.resolveSibling(output.getFileName() + ".lock"), "single attempt",
                StandardOpenOption.CREATE_NEW);
        byte[] protocolBytes = Files.readAllBytes(protocolPath);
        JsonNode protocol = JSON.readTree(protocolBytes);
        ObjectNode archive = JSON.createObjectNode();
        archive.put("protocolSha256", sha(protocolBytes));
        archive.put("startedAt", Instant.now().toString());

        // 只加载本地选项配置类，不扫描业务组件或启动数据库、定时任务。
        try (var context = new AnnotationConfigApplicationContext()) {
            var env = context.getEnvironment();
            env.setActiveProfiles("local-ai");
            var loader = new YamlPropertySourceLoader();
            var hashes = archive.putObject("configSha256");
            // 与正常配置优先级一致：环境变量高于profile，profile高于基础文件。
            for (String file : List.of("application-local-ai.yaml", "application.yaml")) {
                Path path = Path.of("backend/src/main/resources", file);
                hashes.put(file, sha(Files.readAllBytes(path)));
                var sources = loader.load(file, new FileSystemResource(path));
                for (var source : sources) env.getPropertySources().addLast(source);
                require(hashes.path(file).asText().equals(protocol.path("configSha256").path(file).asText()),
                        "配置已变化，必须重新审查协议，不能继续调用");
            }
            URI uri = URI.create(env.getRequiredProperty("spring.ai.ollama.base-url"));
            require(uri.toString().equals("http://127.0.0.1:11435"), "探针只允许固定本机服务");
            var options = Binder.get(env).bind("spring.ai.ollama.chat.options", OllamaChatOptions.class)
                    .orElseThrow(() -> new IllegalStateException("缺少本地模型参数配置"));
            String modelName = env.getRequiredProperty("opspilot.forecast.gold.model-name");
            require(modelName.equals(protocol.path("model").asText()) && modelName.equals(options.getModel()), "模型配置不符");
            context.register(LocalAiConfiguration.class);
            context.refresh();
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000); factory.setReadTimeout(60000);
            var metadata = RestClient.builder().baseUrl(uri.toString()).requestFactory(factory).build();
            JsonNode beforeVersion = get(metadata, "/api/version");
            JsonNode before = model(get(metadata, "/api/tags"), modelName);
            require(before.path("digest").asText().equals(protocol.path("digest").asText()), "调用前模型摘要不符");
            archive.set("beforeVersion", beforeVersion);
            archive.set("beforeModel", before);
            archive.put("beforeCheckedAt", Instant.now().toString());
            // 在网络边界捕获SDK实际序列化的请求，不能拿配置对象冒充出站参数。
            AtomicReference<JsonNode> request = new AtomicReference<>();
            var http = RestClient.builder().requestFactory(factory).requestInterceptor((req, body, execution) -> {
                require(req.getURI().toString().equals(uri + "/api/chat"), "SDK请求地址不符");
                require(request.compareAndSet(null, JSON.readTree(body)), "不允许重复模型请求");
                // 即使超时或正文无效，也保留已经实际发送的参数，不能在失败后丢失证据。
                archive.set("request", request.get());
                archive.put("requestCapturedAt", Instant.now().toString());
                return execution.execute(req, body);
            });
            var api = OllamaApi.builder().baseUrl(uri.toString()).restClientBuilder(http).build();
            var chat = OllamaChatModel.builder().ollamaApi(api).defaultOptions(options).build();
            var builder = ChatClient.builder(chat);
            context.getBean(ChatClientCustomizer.class).customize(builder);
            var gateway = new SpringAiGoldForecastGateway(builder, JSON, new GoldForecastProperties(modelName));
            archive.put("modelCallStartedAt", Instant.now().toString());
            var prompt = new GoldForecastPrompt(GoldForecastPromptBuilder.FORMAL_VERSION, INPUT,
                    sha(INPUT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var result = gateway.generate(prompt);
            archive.put("modelCallEndedAt", Instant.now().toString());
            new GoldForecastValidator().validateFormal(result.content());
            require(result.content().signal() == ForecastSignal.ABSTAINED, "无行情探针不得输出方向信号");
            archive.set("request", request.get());
            archive.put("reportedModel", result.reportedModel());
            archive.set("response", JSON.valueToTree(result.content()));
            archive.set("afterModel", model(get(metadata, "/api/tags"), modelName));
            archive.set("loadedModel", model(get(metadata, "/api/ps"), modelName));
            archive.set("afterVersion", get(metadata, "/api/version"));
            archive.put("afterCheckedAt", Instant.now().toString());
            archive.put("scope", "本次探针的请求参数与服务端摘要观察；非历史预测回填、非执行工件认证、非准确率实验");
            archive.put("status", "COMPLETED");
        } catch (Exception error) {
            archive.put("status", "FAILED");
            archive.put("failureType", error.getClass().getSimpleName());
            throw error;
        } finally {
            archive.put("attemptEndedAt", Instant.now().toString());
            byte[] bytes = JSON.writeValueAsBytes(archive);
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW);
            System.out.println(JSON.writeValueAsString(Map.of("archiveSha256", sha(bytes),
                    "status", archive.path("status").asText(), "scope", "无行情、无数据库、非预测成绩")));
        }
    }

    private static JsonNode get(RestClient client, String path) throws Exception {
        return JSON.readTree(client.get().uri(path).retrieve().body(String.class));
    }

    private static JsonNode model(JsonNode root, String name) {
        var matches = new ArrayList<JsonNode>();
        for (JsonNode node : root.path("models")) if (name.equals(node.path("name").asText())) matches.add(node);
        require(matches.size() == 1, "模型列表缺少唯一匹配，不能猜测身份");
        return matches.getFirst();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalStateException(reason);
    }
}
