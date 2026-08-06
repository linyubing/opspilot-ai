package com.opspilot.ai.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;


@Component
public class SpringAiChatGateway implements ChatGateway {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatGateway.class);

    private final ChatClient chatClient;

    public SpringAiChatGateway(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String generate(String message) {
        long startTime = System.nanoTime();

        try {
            String content = chatClient.prompt()
                    .user(message)
                    .call()
                    .content();

            long elapsedNanos = System.nanoTime() -startTime;
            long elapsedMillis = Duration.ofNanos(elapsedNanos).toMillis();

            log.info(
                    "AI 对话调用完成，问题长度={}，回答长度={}，耗时={}毫秒",
                    message.length(),
                    content.length(),
                    elapsedMillis
            );

            return content;
        } catch (RuntimeException exception) {
            long elapsedMillis = elapsedMillis(startTime);

            log.warn(
                    "AI 对话调用失败，问题长度={}，耗时={}毫秒，异常类型={}",
                    message.length(),
                    elapsedMillis,
                    exception.getClass().getSimpleName()
            );
            throw  new UpstreamAiException(
                    "AI 服务暂时不可用，请稍后重试",
                    exception
            );
        }


    }

    private  long elapsedMillis(long startTime){
        long elapsedNanos = System.nanoTime()-startTime;
        return Duration.ofNanos(elapsedNanos).toMillis();
    }
}
