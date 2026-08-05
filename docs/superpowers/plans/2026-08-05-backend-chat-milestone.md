# OpsPilot AI 后端对话里程碑实现计划

> **执行要求：** 实现时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项完成本计划；使用复选框（`- [ ]`）跟踪进度。

**目标：** 构建经过测试的 Spring Boot 后端，提供 `POST /api/chat` 接口，并通过 Spring AI 调用智谱 `glm-4.7`。

**架构：** HTTP 适配层把请求交给轻量应用服务；应用服务依赖 `ChatGateway` 端口。Spring AI 适配器使用 `ChatClient` 实现该端口；测试使用内存替代实现，因此日常测试不需要网络，也不会消耗模型额度。

**技术栈：** Java 21、Spring Boot 3.5.x、Spring AI 1.1.8、Maven Wrapper、Spring MVC、Jakarta Validation、JUnit 5、AssertJ、MockMvc。

## 全局约束

- 使用 Java 21 和 Spring Boot 3.5.x。
- 使用 Spring AI 1.1.8 和 `spring-ai-starter-model-openai`。
- 使用模型 `glm-4.7` 连接 `https://open.bigmodel.cn/api/paas/v4`。
- API Key 只能从 `ZHIPU_API_KEY` 读取，绝不能提交真实密钥。
- 首个里程碑保持同步、无状态；不实现流式响应、持久化、RAG、Agent、MCP、前端、Docker 或身份认证。
- 自动化测试不得调用真实智谱 API，也不得消耗模型额度。
- 遵循红—绿—重构：每个生产行为都必须先有一个因预期原因失败的测试。

---

## 文件结构与职责

- `backend/pom.xml`：管理依赖和 Java 版本。
- `backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/*`：保证 Maven 构建可重复执行。
- `backend/src/main/java/com/opspilot/ai/OpsPilotApplication.java`：Spring Boot 启动入口。
- `backend/src/main/java/com/opspilot/ai/chat/ChatGateway.java`：与模型供应商无关的调用端口。
- `backend/src/main/java/com/opspilot/ai/chat/ChatService.java`：对话业务用例。
- `backend/src/main/java/com/opspilot/ai/chat/SpringAiChatGateway.java`：Spring AI 适配器。
- `backend/src/main/java/com/opspilot/ai/chat/UpstreamAiException.java`：安全表示上游调用失败。
- `backend/src/main/java/com/opspilot/ai/chat/api/ChatController.java`：HTTP 接口。
- `backend/src/main/java/com/opspilot/ai/chat/api/ChatRequest.java`：带校验规则的请求 DTO。
- `backend/src/main/java/com/opspilot/ai/chat/api/ChatResponse.java`：响应 DTO。
- `backend/src/main/java/com/opspilot/ai/common/api/ApiError.java`：安全的错误响应结构。
- `backend/src/main/java/com/opspilot/ai/common/api/GlobalExceptionHandler.java`：HTTP 异常映射。
- `backend/src/main/resources/application.yml`：不包含密钥的模型配置。
- `backend/src/test/**`：单元测试、接口测试和上下文测试。
- `.env.example`：不包含真实密钥的环境变量示例。
- `.gitignore`：排除 IDE、构建产物和密钥文件。
- `README.md`：安装、测试、运行和手工验证说明。

### 任务 1：可重复构建的 Spring Boot 骨架

**涉及文件：**
- 创建：`backend/pom.xml`
- 创建：`backend/mvnw`
- 创建：`backend/mvnw.cmd`
- 创建：`backend/.mvn/wrapper/maven-wrapper.properties`
- 创建：`backend/src/main/java/com/opspilot/ai/OpsPilotApplication.java`
- 测试：`backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**接口关系：**
- 输入依赖：JDK 21 和 Maven Central。
- 交付结果：可启动的 `com.opspilot.ai.OpsPilotApplication`，以及可执行的 `backend/mvnw.cmd test`。

- [ ] **Step 1: Generate only the build wrapper and empty application structure**

从 Spring Initializr 下载 Maven 项目：Java 21、Boot 3.5.x、group 为 `com.opspilot`、artifact 为 `backend`、包名为 `com.opspilot.ai`，依赖选择 `web,validation`。保留生成的 Maven Wrapper，并在 `pom.xml` 中添加 Spring AI 1.1.8 BOM 和 `spring-ai-starter-model-openai` 依赖。

The dependency-management fragment must be:

```xml
<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.8</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

- [ ] **Step 2: Write the context test before the application class**

```java
package com.opspilot.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.model.chat=none")
class OpsPilotApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

运行：`cd backend; .\mvnw.cmd -Dtest=OpsPilotApplicationTests test`

预期：因为 `OpsPilotApplication` 不存在而发生编译或上下文启动失败。

- [ ] **Step 4: Add the minimal application entry point**

```java
package com.opspilot.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OpsPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsPilotApplication.class, args);
    }
}
```

- [ ] **Step 5: Run the test and verify GREEN**

运行：`cd backend; .\mvnw.cmd -Dtest=OpsPilotApplicationTests test`

预期：显示 `BUILD SUCCESS`，一个测试通过，且未调用真实模型。

- [ ] **Step 6: Commit the skeleton**

```powershell
git add -- backend/pom.xml backend/mvnw backend/mvnw.cmd backend/.mvn backend/src/main/java/com/opspilot/ai/OpsPilotApplication.java backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "build: create Spring Boot backend skeleton"
```

### 任务 2：与模型供应商无关的对话用例

**涉及文件：**
- 创建：`backend/src/main/java/com/opspilot/ai/chat/ChatGateway.java`
- 创建：`backend/src/main/java/com/opspilot/ai/chat/ChatService.java`
- 测试：`backend/src/test/java/com/opspilot/ai/chat/ChatServiceTests.java`

**接口关系：**
- 输入依赖：除 JDK 外无其他依赖。
- 交付结果：`ChatGateway#generate(String): String` 和 `ChatService#chat(String): String`。

- [ ] **Step 1: Write the failing service test**

```java
package com.opspilot.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatServiceTests {
    @Test
    void returnsTextGeneratedByGateway() {
        ChatGateway gateway = message -> "reply to: " + message;
        ChatService service = new ChatService(gateway);

        assertThat(service.chat("hello")).isEqualTo("reply to: hello");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

运行：`cd backend; .\mvnw.cmd -Dtest=ChatServiceTests test`

预期：因为 `ChatGateway` 和 `ChatService` 不存在，测试编译失败。

- [ ] **Step 3: Add the minimal port and service**

```java
package com.opspilot.ai.chat;

@FunctionalInterface
public interface ChatGateway {
    String generate(String message);
}
```

```java
package com.opspilot.ai.chat;

import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final ChatGateway gateway;

    public ChatService(ChatGateway gateway) {
        this.gateway = gateway;
    }

    public String chat(String message) {
        return gateway.generate(message);
    }
}
```

- [ ] **Step 4: Run the test and verify GREEN**

运行：`cd backend; .\mvnw.cmd -Dtest=ChatServiceTests test`

预期：显示 `BUILD SUCCESS`，一个测试通过。

- [ ] **Step 5: Commit the use case**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/chat/ChatGateway.java backend/src/main/java/com/opspilot/ai/chat/ChatService.java backend/src/test/java/com/opspilot/ai/chat/ChatServiceTests.java
git commit -m "feat: add provider-independent chat service"
```

### 任务 3：带参数校验的 HTTP 对话接口

**涉及文件：**
- 创建：`backend/src/main/java/com/opspilot/ai/chat/api/ChatRequest.java`
- 创建：`backend/src/main/java/com/opspilot/ai/chat/api/ChatResponse.java`
- 创建：`backend/src/main/java/com/opspilot/ai/chat/api/ChatController.java`
- 测试：`backend/src/test/java/com/opspilot/ai/chat/api/ChatControllerTests.java`

**接口关系：**
- 输入依赖：`ChatService#chat(String): String`。
- 交付结果：`POST /api/chat`、请求 `ChatRequest(String message)`、响应 `ChatResponse(String content)`。

- [ ] **Step 1: Write the failing controller tests**

```java
package com.opspilot.ai.chat.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.ai.chat.ChatGateway;
import com.opspilot.ai.chat.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTests {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ChatGateway gateway = message -> "reply to: " + message;
        ChatController controller = new ChatController(new ChatService(gateway));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsGeneratedContent() throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("reply to: hello"));
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

运行：`cd backend; .\mvnw.cmd -Dtest=ChatControllerTests test`

预期：因为 API record 和 Controller 不存在而编译失败。

- [ ] **Step 3: Add request, response, and controller**

```java
package com.opspilot.ai.chat.api;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank(message = "message must not be blank") String message) {
}
```

```java
package com.opspilot.ai.chat.api;

public record ChatResponse(String content) {
}
```

```java
package com.opspilot.ai.chat.api;

import com.opspilot.ai.chat.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return new ChatResponse(service.chat(request.message()));
    }
}
```

- [ ] **Step 4: Run the tests and verify GREEN**

运行：`cd backend; .\mvnw.cmd -Dtest=ChatControllerTests test`

预期：显示 `BUILD SUCCESS`，两个测试通过。

- [ ] **Step 5: Commit the endpoint**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/chat/api backend/src/test/java/com/opspilot/ai/chat/api
git commit -m "feat: expose validated chat endpoint"
```

### 任务 4：Spring AI 适配器与安全的上游错误处理

**涉及文件：**
- 创建：`backend/src/main/java/com/opspilot/ai/chat/UpstreamAiException.java`
- 创建：`backend/src/main/java/com/opspilot/ai/chat/SpringAiChatGateway.java`
- 创建：`backend/src/main/java/com/opspilot/ai/common/api/ApiError.java`
- 创建：`backend/src/main/java/com/opspilot/ai/common/api/GlobalExceptionHandler.java`
- 测试：`backend/src/test/java/com/opspilot/ai/chat/SpringAiChatGatewayTests.java`
- 测试：`backend/src/test/java/com/opspilot/ai/common/api/GlobalExceptionHandlerTests.java`

**接口关系：**
- 输入依赖：Spring AI `ChatClient.Builder` 和 `ChatGateway#generate(String)`。
- 交付结果：实现 `ChatGateway` 的 Spring Bean、`UpstreamAiException`，以及 HTTP 502 响应 `ApiError(String code, String message)`。

- [ ] **Step 1: Write a failing adapter error test**

使用 Spring AI 可测试的 `ChatModel` 接口构造一个会抛出异常的 `ChatClient`，然后断言 `SpringAiChatGateway#generate` 抛出 `UpstreamAiException`，安全提示为 `AI service is unavailable`。不要断言或暴露供应商的原始错误信息。

```java
assertThatThrownBy(() -> gateway.generate("hello"))
    .isInstanceOf(UpstreamAiException.class)
    .hasMessage("AI service is unavailable");
```

- [ ] **Step 2: Run the adapter test and verify RED**

运行：`cd backend; .\mvnw.cmd -Dtest=SpringAiChatGatewayTests test`

预期：因为适配器和异常类型不存在而编译失败。

- [ ] **Step 3: Implement the Spring AI adapter**

```java
package com.opspilot.ai.chat;

public class UpstreamAiException extends RuntimeException {
    public UpstreamAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.opspilot.ai.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class SpringAiChatGateway implements ChatGateway {
    private final ChatClient chatClient;

    public SpringAiChatGateway(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String generate(String message) {
        try {
            return chatClient.prompt().user(message).call().content();
        } catch (RuntimeException exception) {
            throw new UpstreamAiException("AI service is unavailable", exception);
        }
    }
}
```

- [ ] **Step 4: Run the adapter test and verify GREEN**

运行：`cd backend; .\mvnw.cmd -Dtest=SpringAiChatGatewayTests test`

预期：显示 `BUILD SUCCESS`；模型原始异常只作为 cause 保留。

- [ ] **Step 5: Write the failing HTTP 502 test**

使用会抛出 `UpstreamAiException` 的 `ChatGateway` 构造独立 MockMvc，注册 `GlobalExceptionHandler`，并断言：

```java
.andExpect(status().isBadGateway())
.andExpect(jsonPath("$.code").value("UPSTREAM_AI_ERROR"))
.andExpect(jsonPath("$.message").value("AI service is unavailable"));
```

- [ ] **Step 6: Run the handler test and verify RED**

运行：`cd backend; .\mvnw.cmd -Dtest=GlobalExceptionHandlerTests test`

预期：因为 `ApiError` 和 `GlobalExceptionHandler` 不存在而编译失败。

- [ ] **Step 7: Implement the safe error response**

```java
package com.opspilot.ai.common.api;

public record ApiError(String code, String message) {
}
```

```java
package com.opspilot.ai.common.api;

import com.opspilot.ai.chat.UpstreamAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UpstreamAiException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ApiError handleUpstreamAi(UpstreamAiException exception) {
        return new ApiError("UPSTREAM_AI_ERROR", "AI service is unavailable");
    }
}
```

- [ ] **Step 8: Run all focused tests and verify GREEN**

运行：`cd backend; .\mvnw.cmd -Dtest=SpringAiChatGatewayTests,GlobalExceptionHandlerTests,ChatControllerTests test`

预期：显示 `BUILD SUCCESS`；适配器、异常处理器和 Controller 测试全部通过。

- [ ] **Step 9: Commit the adapter and error mapping**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/chat/SpringAiChatGateway.java backend/src/main/java/com/opspilot/ai/chat/UpstreamAiException.java backend/src/main/java/com/opspilot/ai/common/api backend/src/test/java/com/opspilot/ai/chat/SpringAiChatGatewayTests.java backend/src/test/java/com/opspilot/ai/common/api/GlobalExceptionHandlerTests.java
git commit -m "feat: connect chat service through Spring AI"
```

### 任务 5：安全配置、学习文档与真实模型验证

**涉及文件：**
- 创建：`backend/src/main/resources/application.yml`
- 创建：`.env.example`
- 创建：`.gitignore`
- 创建：`README.md`
- 修改：`backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**接口关系：**
- 输入依赖：环境变量 `ZHIPU_API_KEY`。
- 交付结果：清晰记录的本地运行流程，以及配置完成的 `glm-4.7` 客户端。

- [ ] **Step 1: Add non-secret configuration**

```yaml
spring:
  application:
    name: opspilot-backend
  ai:
    openai:
      base-url: https://open.bigmodel.cn/api/paas/v4
      api-key: ${ZHIPU_API_KEY}
      chat:
        options:
          model: glm-4.7
    retry:
      max-attempts: 3
      backoff:
        initial-interval: 1s
        multiplier: 2
        max-interval: 5s
```

- [ ] **Step 2: Add secret-safe repository files**

`.env.example`:

```dotenv
ZHIPU_API_KEY=replace-with-your-own-key
```

`.gitignore` must contain:

```gitignore
.idea/
.env
*.iml
backend/target/
```

- [ ] **Step 3: Keep the context test network-free**

保留 `@SpringBootTest(properties = "spring.ai.model.chat=none")`；如果应用上下文仍需要 `ChatGateway`，则增加一个仅供测试使用并标记为 `@Primary` 的 Bean，固定返回 `test response`。

- [ ] **Step 4: Document exact learning workflow**

`README.md` must explain prerequisites, module boundaries, `ZHIPU_API_KEY` setup, test command, run command, curl/PowerShell request, expected response, and why tests use a fake gateway.

PowerShell 环境变量设置与启动示例：

```powershell
$env:ZHIPU_API_KEY='your-real-key'
cd backend
.\mvnw.cmd spring-boot:run
```

手工请求示例：

```powershell
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/chat' `
  -ContentType 'application/json' `
  -Body '{"message":"请用一句话介绍 OpsPilot AI"}'
```

- [ ] **Step 5: Run the full automated suite**

运行：`cd backend; .\mvnw.cmd test`

预期：显示 `BUILD SUCCESS`；没有测试读取 `ZHIPU_API_KEY` 或访问网络。

- [ ] **Step 6: Scan for leaked secrets and unwanted files**

运行：

```powershell
git status --short
git grep -n -I -E 'Bearer [A-Za-z0-9._-]+|ZHIPU_API_KEY=[^r]' -- ':!docs/superpowers/**'
```

预期：没有匹配到真实密钥；`.env` 和 `.idea/` 未被暂存。

- [ ] **Step 7: Perform one authorized real-model verification**

只在当前终端设置 `ZHIPU_API_KEY`，启动应用并调用 `POST /api/chat`。预期获得 HTTP 200，且 `content` 非空。不得把密钥复制到将被提交的命令、日志、截图或聊天消息中。

- [ ] **Step 8: Commit configuration and learning documentation**

```powershell
git add -- .gitignore .env.example README.md backend/src/main/resources/application.yml backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "docs: add secure setup and verification guide"
```

- [ ] **Step 9: Final verification**

运行：

```powershell
cd backend
.\mvnw.cmd clean test
git status --short
```

预期：显示 `BUILD SUCCESS`；提交范围外仅保留先前已存在、且有意不跟踪的项目材料。

## 官方参考资料

- Spring AI compatibility: https://github.com/spring-projects/spring-ai
- Spring AI OpenAI chat configuration: https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html
- Zhipu OpenAI compatibility: https://docs.bigmodel.cn/cn/guide/develop/openai/introduction
- GLM-4.7 model: https://docs.bigmodel.cn/cn/guide/models/text/glm-4.7
