package com.opspilot.ai;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.rag.RagService;
import com.opspilot.ai.retrieval.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
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

	@Autowired
	private EmbeddingModel embeddingModel;

	@Test
	void createsOllamaEmbeddingModel() {
		/*
		 * 验证运行时真正注入的是 Ollama 实现，
		 * 而不是只验证配置文件中写了 ollama。
		 */
		assertThat(embeddingModel)
				.isInstanceOf(OllamaEmbeddingModel.class);
	}

	@Test
	void usesOllamaEmbeddingProvider() {
		/*
		 * provider 表示供应商。
		 * 这个配置决定 Spring AI 创建哪一种 EmbeddingModel。
		 */
		assertThat(environment.getProperty(
				"spring.ai.model.embedding"
		)).isEqualTo("ollama");
	}

	@Test
	void usesLocalEmbeddingModel() {
		// 验证 Ollama 使用的具体向量模型。
		assertThat(environment.getProperty(
				"spring.ai.ollama.embedding.model"
		)).isEqualTo("nomic-embed-text");
	}

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
