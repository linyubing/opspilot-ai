package com.opspilot.ai.chat;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ChatServiceTests {

    @Test
    void returnsTextGeneratedByGateway(){
        ChatGateway gateway = message -> "回复: "+ message;
        ChatService service = new ChatService(gateway);

        String result = service.chat("你好");

        assertThat(result).isEqualTo("回复: 你好");
    }
}
