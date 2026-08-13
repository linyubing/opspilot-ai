package com.opspilot.ai.rag.api;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.rag.RagPromptBuilder;
import com.opspilot.ai.rag.RagService;
import com.opspilot.ai.retrieval.KnowledgeSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class RagControllerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(){
        KnowledgeSearchService searchService =
                mock(KnowledgeSearchService.class);

        ChatGateway chatGateway =
                message -> "请先检查 CPU 消耗较高的进程。";

        String question = "CPU 使用率持续过高怎么排查？";

        when(searchService.search(question)).thenReturn(List.of(
                new Document("CPU 过高时，应检查消耗 CPU 较高的进程。")
        ));

        RagService ragService = new RagService(searchService,new RagPromptBuilder(),chatGateway);

        /*
         * 当前测试先假设 RagController 已经存在，
         * 通过编译失败证明下一步确实需要实现这个 HTTP 入口。
         */
        RagController controller = new RagController(ragService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsRagAnswer() throws  Exception{
        mockMvc.perform(post("/api/rag/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "CPU 使用率持续过高怎么排查？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.content")
                        .value("请先检查 CPU 消耗较高的进程。"));
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/rag/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "message": "  "
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
