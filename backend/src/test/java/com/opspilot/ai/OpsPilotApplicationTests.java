package com.opspilot.ai;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.rag.RagService;
import com.opspilot.ai.retrieval.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class OpsPilotApplicationTests {

	@MockitoBean
	private ChatGateway chatGateway;

	// required=false：Bean 不存在时先让容器启动，再由测试给出清晰的失败结果
	@Autowired(required = false)
	private KnowledgeSearchService knowledgeSearchService;

	@Autowired
	private Environment environment;

	// 没有 VectorStore Bean 时允许启动测试，随后通过断言明确失败原因
	@Autowired(required = false)
	private VectorStore vectorStore;

	@Autowired
	private RagService ragService;

	@Test
	void loadsRagService(){
		//验证Spring 容器能够完成RAG整条依赖链的装配
		assertThat(ragService).isNotNull();
	}

	@Test
	void createsKnowledgeSearchService(){
		assertThat(knowledgeSearchService).isNotNull();
	}

	@Test
	void usesZhipuEmbeddingPath(){
		//智谱基础地址已经包含v4,因此不能继续使用默认的/v1/embeddings
		assertThat(environment.getProperty( "spring.ai.openai.embedding.embeddings-path")).isEqualTo("/embeddings");
	}

	@Test
	void usesZhipuEmbeddingModel(){
		//确保Spring AI 不会使用默认的OpenAI 向量模型
		assertThat(environment.getProperty("spring.ai.openai.embedding.options.model")).isEqualTo("embedding-3");
	}

	@Test
	void createsVectorStore(){
		//验证摄取模块已经接入真正的向量存储抽象
		assertThat(vectorStore).isNotNull();
	}

	@Test
	void contextLoads() {
	}

	@Test
	void usesZhipuCompatibleChatCompletionsPath() {
		assertThat(environment.getProperty("spring.ai.openai.chat.completions-path"))
				.isEqualTo("/chat/completions");
	}

}
