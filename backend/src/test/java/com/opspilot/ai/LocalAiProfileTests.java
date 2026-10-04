package com.opspilot.ai;

import com.opspilot.ai.forecast.GoldForecastProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证本地配置实际切换模型，并让记录中的模型名与调用保持一致。 */
@ActiveProfiles("local-ai")
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class LocalAiProfileTests {

    @Autowired private ChatModel chat;
    @Autowired private EmbeddingModel embedding;
    @Autowired private GoldForecastProperties forecast;
    @Autowired private Environment env;
    @Autowired private ApplicationContext context;

    @Test
    void disablesThinkingForClientCalls() {
        // 真实 ChatClient 发出的选项必须关闭思考，避免思考耗尽额度后正文为空。
        AtomicReference<ChatOptions> sent = new AtomicReference<>();
        ChatModel recorder = prompt -> {
            sent.set(prompt.getOptions());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("正常正文"))));
        };
        ChatClient.Builder builder = ChatClient.builder(recorder);
        context.getBeansOfType(ChatClientCustomizer.class).values()
                .forEach(customizer -> customizer.customize(builder));
        assertThat(builder.build().prompt().user("请回复正文").call().content())
                .isEqualTo("正常正文");
        assertThat(sent.get()).isInstanceOf(OllamaChatOptions.class);
        assertThat(((OllamaChatOptions) sent.get()).getThinkOption())
                .isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    }

    @Test
    void selectsLocalChat() {
        // 只切配置文字不够：实际聊天 Bean 也必须是本地实现。
        assertThat(chat).isInstanceOf(OllamaChatModel.class);
    }

    @Test
    void keepsModelNamesAligned() {
        // 模型名参与幂等键和结果分组，不能继续把本地结果记作 GLM。
        assertThat(forecast.modelName()).isEqualTo("qwen3.5:9b");
        assertThat(env.getProperty("opspilot.research.narrative.model-name"))
                .isEqualTo("qwen3.5:9b");
        assertThat(env.getProperty("spring.ai.ollama.chat.options.model"))
                .isEqualTo("qwen3.5:9b");
    }

    @Test
    void usesSameLocalServerForEmbedding() {
        assertThat(embedding).isInstanceOf(OllamaEmbeddingModel.class);
        assertThat(env.getProperty("spring.ai.ollama.base-url"))
                .isEqualTo("http://127.0.0.1:11435");
        assertThat(env.getProperty("spring.ai.ollama.embedding.model"))
                .isEqualTo("nomic-embed-text");
    }

    @Test
    void disablesExternalSchedules() {
        // 本地启动不应顺带请求行情供应商或触发正式预测。
        assertThat(env.getProperty("opspilot.forecast.gold.settlement-schedule.enabled"))
                .isEqualTo("false");
        assertThat(env.getProperty("opspilot.research.gold.daily-report-schedule.enabled"))
                .isEqualTo("false");
    }
}
