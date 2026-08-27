package com.opspilot.ai.forecast;

import java.time.Duration;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** 通过 Spring AI 调用模型并解析结构化黄金方向预测。 */
@Component
public class SpringAiGoldForecastGateway implements GoldForecastGateway {

    private static final Logger log = LoggerFactory.getLogger(
            SpringAiGoldForecastGateway.class
    );

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public SpringAiGoldForecastGateway(
            ChatClient.Builder builder,
            ObjectMapper objectMapper,
            GoldForecastProperties properties
    ) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.modelName = properties.modelName();
    }

    @Override
    public GeneratedGoldForecast generate(GoldForecastPrompt prompt) {
        Objects.requireNonNull(prompt, "黄金方向预测提示词不能为空");
        long startTime = System.nanoTime();

        String rawResponse = callModel(prompt, startTime);
        GoldDirectionForecastContent content = parse(rawResponse);
        log.info(
                "黄金方向预测模型调用完成，模型={}，提示词长度={}，响应长度={}，耗时={}毫秒",
                modelName, prompt.content().length(), rawResponse.length(),
                elapsedMillis(startTime)
        );
        return new GeneratedGoldForecast(modelName, rawResponse, content);
    }

    /** 隔离远程调用，避免把本地 JSON 解析错误误判为上游不可用。 */
    private String callModel(GoldForecastPrompt prompt, long startTime) {
        try {
            return chatClient.prompt().user(prompt.content()).call().content();
        } catch (RuntimeException exception) {
            log.warn(
                    "黄金方向预测模型调用失败，模型={}，提示词长度={}，耗时={}毫秒，异常类型={}",
                    modelName, prompt.content().length(), elapsedMillis(startTime),
                    exception.getClass().getSimpleName()
            );
            throw new GoldForecastAiUnavailableException(
                    "黄金方向预测模型暂时不可用，请稍后重试", exception
            );
        }
    }

    /** 解析模型 JSON；原始响应只向内部返回，不写入日志。 */
    private GoldDirectionForecastContent parse(String rawResponse) {
        try {
            return objectMapper.readValue(rawResponse, GoldDirectionForecastContent.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidGoldForecastAiResponseException(
                    "大模型未返回合法的结构化黄金方向预测", exception
            );
        }
    }

    private long elapsedMillis(long startTime) {
        return Duration.ofNanos(System.nanoTime() - startTime).toMillis();
    }
}
