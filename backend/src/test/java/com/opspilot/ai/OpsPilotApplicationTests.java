package com.opspilot.ai;

import com.opspilot.ai.chat.ChatGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class OpsPilotApplicationTests {

	@MockitoBean
	private ChatGateway chatGateway;

	@Autowired
	private Environment environment;

	@Test
	void contextLoads() {
	}

	@Test
	void usesZhipuCompatibleChatCompletionsPath() {
		assertThat(environment.getProperty("spring.ai.openai.chat.completions-path"))
				.isEqualTo("/chat/completions");
	}

}
