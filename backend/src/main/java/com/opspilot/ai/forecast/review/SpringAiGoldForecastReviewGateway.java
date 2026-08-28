package com.opspilot.ai.forecast.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.ai.forecast.GoldForecastProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * 通过 Spring AI 调用模型并解析结构化黄金预测复盘。
 */
@Component
public class SpringAiGoldForecastReviewGateway implements GoldForecastReviewGateway {

    private static final Logger log = LoggerFactory.getLogger(
            SpringAiGoldForecastReviewGateway.class
    );

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public SpringAiGoldForecastReviewGateway(
            ChatClient.Builder builder,
            ObjectMapper objectMapper,
            GoldForecastProperties properties
    ) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.modelName = properties.modelName();
    }

    private long elapsedMillis(long startTime) {
        return Duration.ofNanos(
                System.nanoTime() - startTime
        ).toMillis();
    }

    @Override
    public GeneratedGoldForecastReview generate(GoldForecastReviewPrompt prompt) {
        Objects.requireNonNull(
                prompt,
                "黄金预测复盘提示词不能为空"
        );

        long startTime = System.nanoTime();

        String rawResponse;
        /*
         * 模型调用失败属于外部服务不可用，
         * 不能和模型返回错误 JSON 混为同一种异常。
         */
        try {
            rawResponse = chatClient.prompt().user(prompt.content()).call().content();
        } catch (RuntimeException exception) {
            log.warn(
                    "黄金预测复盘模型调用失败，模型={}，提示词长度={}，"
                            + "耗时={}毫秒，异常类型={}",
                    modelName,
                    prompt.content().length(),
                    elapsedMillis(startTime),
                    exception.getClass().getSimpleName()
            );

            throw new GoldForecastReviewAiUnavailableException(
                    "黄金预测复盘模型暂时不可用，请稍后重试",
                    exception
            );
        }
        GoldForecastReviewContent content;
        /*
         * 调用成功但 JSON 不合法，说明模型没有遵守结构化输出合同。
         * 原始响应不能打印到日志，避免泄露完整模型内容。
         */
        try {
            content = objectMapper.readValue(rawResponse, GoldForecastReviewContent.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidGoldForecastReviewAiResponseException(
                    "大模型未返回合法的结构化黄金预测复盘",
                    exception
            );
        }

        log.info(
                "黄金预测复盘模型调用完成，模型={}，提示词版本={}，"
                        + "提示词长度={}，响应长度={}，耗时={}毫秒",
                modelName,
                prompt.version(),
                prompt.content().length(),
                rawResponse.length(),
                elapsedMillis(startTime)
        );

        return new GeneratedGoldForecastReview(
                modelName,
                rawResponse,
                content
        );

    }
}
