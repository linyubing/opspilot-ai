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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

public class ChatControllerTests {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(){
        ChatGateway gateway = message -> "回复: " +message;
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
                            "message":"你好"
                        }"""))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.content").value("回复: 你好"));
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
