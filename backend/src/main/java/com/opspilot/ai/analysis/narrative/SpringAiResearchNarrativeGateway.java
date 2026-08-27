package com.opspilot.ai.analysis.narrative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/** 通过 Spring AI 调用模型并解析结构化黄金研究解读。 */
@Component
public class SpringAiResearchNarrativeGateway implements ResearchNarrativeGateway {

    private static final Logger log = LoggerFactory.getLogger(
            SpringAiResearchNarrativeGateway.class
    );

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public SpringAiResearchNarrativeGateway(
            ChatClient.Builder builder,
            ObjectMapper objectMapper,
            ResearchNarrativeProperties properties
    ) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
        this.modelName = properties.modelName();
    }

    @Override
    public GeneratedResearchNarrative generate(ResearchNarrativePrompt prompt) {
        Objects.requireNonNull(prompt, "研究解读提示词不能为空");
        long startTime = System.nanoTime();

        String rawResponse = callModel(prompt, startTime);
        ResearchNarrativeContent content = parseResponse(rawResponse);

        log.info(
                "黄金研究大模型解读完成，模型={}，提示词长度={}，响应长度={}，耗时={}毫秒",
                modelName,
                prompt.content().length(),
                rawResponse.length(),
                elapsedMillis(startTime)
        );

        return new GeneratedResearchNarrative(
                modelName,
                rawResponse,
                content
        );
    }

    /** 单独隔离上游调用，使响应解析错误不会被归类成服务不可用。 */
    private String callModel(
            ResearchNarrativePrompt prompt,
            long startTime
    ) {
        try {
            return chatClient.prompt()
                    .user(prompt.content())
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            log.warn(
                    "黄金研究大模型调用失败，模型={}，提示词长度={}，耗时={}毫秒，异常类型={}",
                    modelName,
                    prompt.content().length(),
                    elapsedMillis(startTime),
                    exception.getClass().getSimpleName()
            );
            throw new ResearchAiUnavailableException(
                    "黄金研究大模型暂时不可用，请稍后重试",
                    exception
            );
        }
    }

    /** 将模型原始 JSON 转换为领域内容，原始响应不写入日志。 */
    private ResearchNarrativeContent parseResponse(String rawResponse) {
        try {
            return objectMapper.readValue(
                    rawResponse,
                    ResearchNarrativeContent.class
            );
        } catch (JsonProcessingException exception) {
            throw new InvalidResearchAiResponseException(
                    "大模型未返回合法的结构化研究解读",
                    exception
            );
        }
    }

    private long elapsedMillis(long startTime) {
        return Duration.ofNanos(
                System.nanoTime() - startTime
        ).toMillis();
    }
}
