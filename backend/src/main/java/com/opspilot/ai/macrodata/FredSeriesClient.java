package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责 FRED 通用序列的 HTTP 请求与响应解析，不包含具体研究语义。
 */
@Component
public class FredSeriesClient {

    private static final String MISSING_VALUE = ".";

    private final RestClient restClient;
    private final FredProperties properties;

    public FredSeriesClient(
            @Qualifier("fredRestClient") RestClient restClient,
            FredProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public FredSeriesBatch fetch(String seriesId) {
        validateRequest(seriesId);

        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fred/series/observations")
                            .queryParam("series_id", seriesId)
                            .queryParam("api_key", properties.apiKey())
                            // FRED 默认返回 XML，这里明确要求 JSON。
                            .queryParam("file_type", "json")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            return parseResponse(root, seriesId);
        } catch (MacroDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            // 不透传远端响应，避免 API Key 或外部服务细节进入业务异常。
            throw new MacroDataUnavailableException(
                    "FRED 序列服务暂时不可用：" + seriesId,
                    exception
            );
        }
    }

    private FredSeriesBatch parseResponse(JsonNode root, String seriesId) {
        if (root == null || root.isNull()) {
            throw new MacroDataUnavailableException(
                    "FRED 返回空响应：" + seriesId
            );
        }

        JsonNode observationsNode = root.get("observations");
        if (observationsNode == null || !observationsNode.isArray()) {
            throw new MacroDataUnavailableException(
                    "FRED 响应缺少 observations 数组：" + seriesId
            );
        }
        if (observationsNode.isEmpty()) {
            throw new MacroDataUnavailableException(
                    "FRED 没有返回序列观测值：" + seriesId
            );
        }

        List<FredSeriesObservation> observations = new ArrayList<>();
        int missingCount = 0;
        for (JsonNode item : observationsNode) {
            String dateText = requiredText(item, "date", seriesId);
            String valueText = requiredText(item, "value", seriesId);

            // FRED 用点号表示缺失值，它不是数值 0，不能写入数据库。
            if (MISSING_VALUE.equals(valueText)) {
                missingCount++;
                continue;
            }

            try {
                observations.add(new FredSeriesObservation(
                        LocalDate.parse(dateText),
                        new BigDecimal(valueText)
                ));
            } catch (DateTimeException | NumberFormatException exception) {
                // 单条格式错误时拒绝整个批次，避免保存不完整的外部数据。
                throw new MacroDataUnavailableException(
                        "FRED 序列观测格式错误：" + seriesId,
                        exception
                );
            }
        }

        return new FredSeriesBatch(
                observations,
                observationsNode.size(),
                missingCount
        );
    }

    private String requiredText(
            JsonNode node,
            String fieldName,
            String seriesId
    ) {
        JsonNode field = node.get(fieldName);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new MacroDataUnavailableException(
                    "FRED 序列观测缺少字段 " + fieldName + "：" + seriesId
            );
        }
        return field.asText();
    }

    private void validateRequest(String seriesId) {
        if (seriesId == null || seriesId.isBlank()) {
            throw new IllegalArgumentException("seriesId 不能为空");
        }
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new MacroDataUnavailableException("未配置 FRED_API_KEY");
        }
    }
}
