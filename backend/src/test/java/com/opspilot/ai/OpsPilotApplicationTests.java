package com.opspilot.ai;

import com.opspilot.ai.chat.ChatGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class OpsPilotApplicationTests {

	@MockitoBean
	private ChatGateway chatGateway;

	@Test
	void contextLoads() {
	}

}
