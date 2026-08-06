package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.chat.ChatService;
import com.opspilot.ai.chat.UpstreamAiException;
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

    @Test
// AI 服务调用失败时，接口应返回 HTTP 502 和统一的错误 JSON
    void aiFailureReturns502() throws Exception {
        // 创建一个必然调用失败的模拟网关
        ChatGateway failingGateway = message -> {
            throw  new UpstreamAiException(
                    "AI 服务暂时不可用，请稍后重试",
                    new IllegalStateException("上游原始错误")
            );
        };

        ChatService service =new ChatService(failingGateway);
        ChatController controller = new ChatController(service);

        /*
         * standaloneSetup：只加载指定的 Controller，不启动完整 Spring 容器。
         * setControllerAdvice：手动注册全局异常处理器。
         */
        MockMvc failingMockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        failingMockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "message": "你好"
                            }
                            """))
                // isBadGateway 表示期望 HTTP 状态码为 502
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code")
                        .value("AI_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("AI 服务暂时不可用，请稍后重试"));
    }

}
