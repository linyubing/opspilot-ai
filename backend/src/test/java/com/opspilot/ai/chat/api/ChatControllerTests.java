package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.chat.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ChatControllerTests {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(){
        ChatGateway gateway = message -> "reply to: " +message;
        ChatService service = new ChatService(gateway);
        ChatController controller = new ChatController(service);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }
    @Test
    void returnsGeneratedContent() throws Exception{
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "message":"hello"
                        }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("reply to: hello"));
    }

    @Test
    void rejectsBlankMessage() throws Exception{
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                            {
                                "message":"  "
                            }
                        """))
                .andExpect(status().isBadRequest());
    }

}
