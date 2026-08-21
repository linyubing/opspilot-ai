package com.opspilot.ai.macrodata;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class FredRealRateProvider implements RealRateProvider {

    private static final Logger log =
            LoggerFactory.getLogger(FredRealRateProvider.class);

    private static final String UNIT = "percent";
    private static final String PROVIDER = "fred";
    private static final String MISSING_VALUE = ".";

    private final RestClient restClient;
    private final FredProperties properties;

    public FredRealRateProvider(
            @Qualifier("fredRestClient") RestClient restClient,
            FredProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public RealRateBatch fetchDailyObservations() {
        validateApiKey();

        long startedAt = System.nanoTime();

        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fred/series/observations")
                            .queryParam(
                                    "series_id",
                                    properties.seriesId()
                            )
                            .queryParam(
                                    "api_key",
                                    properties.apiKey()
                            )
                            /*
                             * FRED 默认返回 XML，
                             * 所以这里必须明确指定 JSON。
                             */
                            .queryParam("file_type", "json")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            RealRateBatch batch = parseResponse(root);

            long elapsedMillis =
                    (System.nanoTime() - startedAt)
                            / 1_000_000;
            /*
             * 不打印完整 URL 和响应内容，
             * 因为 URL 查询参数中包含 API Key。
             */
            log.info(
                    "FRED 实际利率获取完成，序列={}，收到={}，有效={}，缺失={}，耗时={}毫秒",
                    properties.seriesId(),
                    batch.receivedCount(),
                    batch.observations().size(),
                    batch.missingCount(),
                    elapsedMillis
            );
            return batch;
        } catch (MacroDataUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            /*
             * 不把 FRED 的原始错误响应写进业务异常，
             * 防止远端内容或请求信息泄露。
             */
            throw new MacroDataUnavailableException(
                    "FRED 实际利率服务暂时不可用",
                    exception
            );
        }
    }

    private RealRateBatch parseResponse(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new MacroDataUnavailableException(
                    "FRED 返回空响应"
            );
        }

        JsonNode observationsNode = root.get("observations");
        if (observationsNode == null
                || !observationsNode.isArray()) {
            throw new MacroDataUnavailableException(
                    "FRED 响应缺少 observations 数组"
            );
        }

        if (observationsNode.isEmpty()) {
            throw new MacroDataUnavailableException(
                    "FRED 没有返回实际利率观测值"
            );
        }

        List<IncomingMacroObservation> observations =
                new ArrayList<>();
        int missingCount = 0;

        for (JsonNode item : observationsNode) {
            String dateText =
                    requiredText(item, "date");
            String valueText =
                    requiredText(item, "value");

            /*
             * FRED 使用点号表示当天没有观测值。
             * 它不是 0，不能转换后写入数据库。
             */
            if (MISSING_VALUE.equals(valueText)) {
                missingCount++;
                continue;
            }

            try {
                observations.add(
                        new IncomingMacroObservation(
                                properties.seriesId(),
                                LocalDate.parse(dateText),
                                new BigDecimal(valueText),
                                UNIT,
                                PROVIDER
                        )
                );
            } catch (DateTimeException
                     | NumberFormatException exception) {
                /*
                 * 任意一条格式错误时拒绝整个批次，
                 * 防止只保存一部分外部数据。
                 */
                throw new MacroDataUnavailableException(
                        "FRED 实际利率观测格式错误",
                        exception
                );
            }
        }
        return new RealRateBatch(
                observations,
                observationsNode.size(),
                missingCount
        );
    }

    private String requiredText(
            JsonNode node,
            String fieldName
    ) {
        JsonNode field = node.get(fieldName);

        if (field == null
                || !field.isTextual()
                || field.asText().isBlank()) {
            throw new MacroDataUnavailableException(
                    "FRED 观测缺少字段：" + fieldName
            );
        }

        return field.asText();
    }

    private void validateApiKey() {
        if (properties.apiKey() == null
                || properties.apiKey().isBlank()) {
            throw new MacroDataUnavailableException(
                    "未配置 FRED_API_KEY"
            );
        }
    }
}
