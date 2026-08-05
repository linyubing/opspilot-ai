package com.opspilot.ai.chat;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class ChatServiceTests {

    @Test
    void returnsTextGeneratedByGateway(){
        ChatGateway gateway = message -> "reply to: "+ message;
        ChatService service = new ChatService(gateway);

        String result = service.chat("hello");

        assertThat(result).isEqualTo("reply to: hello");
    }
}
