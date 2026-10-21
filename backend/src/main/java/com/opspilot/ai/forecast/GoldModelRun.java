package com.opspilot.ai.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.*;

/**
 * 单次运行的服务端观察；不认证执行中工件、全部默认参数或历史可得性。
 * checkedBefore/checkedAfter 是前后元数据查询完成时刻，sentAt 是出站时刻，returnedAt 是SDK返回时刻。
 * parameters 仅保存实际发出的控制参数，不保存提示词正文。
 */
public record GoldModelRun(String model, String digest, String serverVersion,
        String promptHash, JsonNode parameters, Instant checkedBefore,
        Instant sentAt, Instant returnedAt, Instant checkedAfter, String ruleVersion) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String RULE = "ollama-service-observation-v1";

    public GoldModelRun(String model, String digest, String serverVersion, String promptHash, JsonNode parameters,
            Instant checkedBefore, Instant sentAt, Instant returnedAt, Instant checkedAfter) {
        this(model, digest, serverVersion, promptHash, parameters, checkedBefore, sentAt, returnedAt, checkedAfter, RULE);
    }

    public GoldModelRun {
        if (!RULE.equals(ruleVersion) || model == null || model.isBlank() || digest == null || !digest.matches("[0-9a-f]{64}")
                || serverVersion == null || !serverVersion.matches("[a-zA-Z0-9.+_-]{1,80}")
                || promptHash == null || !promptHash.matches("[0-9a-f]{64}")
                || checkedBefore == null || sentAt == null || returnedAt == null || checkedAfter == null
                || sentAt.isBefore(checkedBefore) || returnedAt.isBefore(sentAt) || checkedAfter.isBefore(returnedAt)) {
            throw new IllegalArgumentException("本地模型运行观察缺失或不匹配");
        }
        parameters = checkedParameters(model, parameters);
    }

    /** 在真正发送HTTP前校验同一份参数，不等模型消耗完资源才发现配置错配。 */
    static JsonNode checkedParameters(String model, JsonNode parameters) {
        if (parameters == null || !parameters.isObject() || !model.equals(parameters.path("model").asText())
                || !parameters.path("stream").isBoolean() || parameters.path("stream").booleanValue()
                || !parameters.path("think").isBoolean() || parameters.path("think").booleanValue()
                || !parameters.path("options").isObject()) {
            throw new IllegalArgumentException("本地模型运行观察缺失或不匹配");
        }
        var names = new HashSet<String>(); parameters.fieldNames().forEachRemaining(names::add);
        if (!Set.of("model", "think", "stream", "options", "format", "keep_alive").containsAll(names)) {
            throw new IllegalArgumentException("模型运行参数含未声明字段");
        }
        if (parameters.path("options").has("think") && !parameters.path("options").path("think").isBoolean()
                || parameters.path("options").path("think").asBoolean(false)) {
            throw new IllegalArgumentException("本地模型思考开关存在冲突");
        }
        return canonical(parameters);
    }

    @Override public JsonNode parameters() { return parameters.deepCopy(); }

    /** 排序后计算运行配置键；不包含时刻或提示词，避免每条样本被单独分组。 */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String key() {
        String value = ruleVersion + "\n" + model + "\n" + digest + "\n" + serverVersion + "\n" + parameters;
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JSON.createObjectNode();
            var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode(); node.forEach(item -> result.add(canonical(item))); return result;
        }
        if (node.isNumber() && !Double.isFinite(node.doubleValue())) throw new IllegalArgumentException("模型参数不是有限数值");
        return node.deepCopy();
    }
}
