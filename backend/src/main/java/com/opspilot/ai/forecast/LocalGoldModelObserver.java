package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** 观察一次本地正式调用的出站参数与前后摘要；线程状态不会跨调用共享。 */
@Component
@Profile("local-ai")
public class LocalGoldModelObserver implements GoldModelObserver, RestClientCustomizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient metadata;
    private final URI base;
    private final Clock clock;
    private final ThreadLocal<State> active = new ThreadLocal<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LocalGoldModelObserver(@Value("${spring.ai.ollama.base-url}") String baseUrl, Clock clock) {
        this.base = URI.create(baseUrl);
        this.clock = clock;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        // 不使用Boot定制器创建元数据客户端，避免递归拦截自己的查询。
        this.metadata = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    LocalGoldModelObserver(RestClient metadata, URI base, Clock clock) {
        this.metadata = metadata; this.base = base; this.clock = clock;
    }

    @Override public void start(String model, GoldForecastPrompt prompt) {
        try {
            require(active.get() == null);
            require("http".equals(base.getScheme()) && Set.of("localhost", "127.0.0.1").contains(base.getHost())
                    && base.getRawUserInfo() == null && base.getRawQuery() == null && base.getRawFragment() == null
                    && (base.getPath().isEmpty() || base.getPath().equals("/")));
            require(sha(prompt.content()).equals(prompt.sha256()));
            String version = get("/api/version").path("version").asText();
            String digest = model(get("/api/tags"), model).path("digest").asText();
            require(digest.matches("[0-9a-f]{64}") && !version.isBlank());
            active.set(new State(model, digest, version, prompt, clock.instant()));
        } catch (RuntimeException error) { throw unavailable(); }
    }

    @Override public void customize(RestClient.Builder builder) {
        builder.requestInterceptor((request, body, execution) -> {
            State state = active.get();
            if (state != null) {
                String stage = "请求地址";
                try {
                    require(request.getMethod() == HttpMethod.POST && request.getURI().equals(base.resolve("/api/chat")));
                    stage = "重复请求";
                    require(state.parameters == null); // 自动重试不得变成第二次不可追踪的实际调用。
                    stage = "请求解析";
                    JsonNode payload = JSON.readTree(body);
                    var messages = payload.path("messages");
                    stage = "提示词绑定";
                    require(messages.isArray() && messages.size() == 1
                            && "user".equals(messages.get(0).path("role").asText())
                            && state.prompt.content().equals(messages.get(0).path("content").asText()));
                    var messageNames = new java.util.HashSet<String>();
                    messages.get(0).fieldNames().forEachRemaining(messageNames::add);
                    require(Set.of("role", "content").containsAll(messageNames));
                    ObjectNode parameters = payload.deepCopy();
                    parameters.remove("messages");
                    stage = "参数字段";
                    if (parameters.has("tools")) {
                        // SDK可能发空工具列表；非空工具会改变输入合同，必须拒绝而非忽略。
                        require(parameters.path("tools").isArray() && parameters.path("tools").isEmpty());
                        parameters.remove("tools");
                    }
                    state.parameters = GoldModelRun.checkedParameters(state.model, parameters);
                    state.sentAt = clock.instant();
                } catch (Exception error) {
                    throw new GoldForecastAiUnavailableException("本地模型请求观察失败：" + stage, null);
                }
            }
            return execution.execute(request, body);
        });
    }

    @Override public GoldModelRun finish(String reportedModel) {
        try {
            State state = active.get();
            require(state != null && state.model.equals(reportedModel) && state.parameters != null);
            Instant returned = clock.instant();
            require(state.version.equals(get("/api/version").path("version").asText()));
            require(state.digest.equals(model(get("/api/tags"), state.model).path("digest").asText()));
            require(state.digest.equals(model(get("/api/ps"), state.model).path("digest").asText()));
            return new GoldModelRun(state.model, state.digest, state.version, state.prompt.sha256(),
                    state.parameters, state.before, state.sentAt, returned, clock.instant());
        } catch (RuntimeException error) { throw unavailable(); }
    }

    @Override public void clear() { active.remove(); }

    private JsonNode get(String path) {
        try { return JSON.readTree(metadata.get().uri(path).retrieve().body(String.class)); }
        catch (Exception error) { throw unavailable(); }
    }
    private JsonNode model(JsonNode root, String name) {
        JsonNode found = null;
        for (var node : root.path("models")) if (name.equals(node.path("name").asText())) {
            require(found == null); found = node;
        }
        require(found != null); return found;
    }
    private static String sha(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void require(boolean value) { if (!value) throw unavailable(); }
    private GoldForecastAiUnavailableException unavailable() {
        return new GoldForecastAiUnavailableException("本地模型逐次身份或请求参数不可核验，未保存正式预测", null);
    }
    /** 同步调用的线程内状态，结束即清理；不能把中途状态当作完整运行证据。 */
    private static final class State {
        final String model, digest, version;
        final GoldForecastPrompt prompt;
        final Instant before;
        JsonNode parameters;
        Instant sentAt;
        State(String model, String digest, String version, GoldForecastPrompt prompt, Instant before) {
            this.model=model; this.digest=digest; this.version=version; this.prompt=prompt; this.before=before;
        }
    }
}
