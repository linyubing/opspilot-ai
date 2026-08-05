# OpsPilot AI Backend Chat Milestone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a tested Spring Boot backend that exposes `POST /api/chat` and calls Zhipu `glm-4.7` through Spring AI.

**Architecture:** The HTTP adapter delegates to a small application service, which depends on a `ChatGateway` port. A Spring AI adapter implements that port with `ChatClient`; tests use in-memory fakes so normal test runs never require a network connection or consume model quota.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring AI 1.1.8, Maven Wrapper, Spring MVC, Jakarta Validation, JUnit 5, AssertJ, MockMvc.

## Global Constraints

- Use Java 21 and Spring Boot 3.5.x.
- Use Spring AI 1.1.8 and `spring-ai-starter-model-openai`.
- Connect to `https://open.bigmodel.cn/api/paas/v4` with model `glm-4.7`.
- Read the API key only from `ZHIPU_API_KEY`; never commit a real key.
- The first milestone is synchronous and stateless; no streaming, persistence, RAG, Agent, MCP, frontend, Docker, or authentication.
- Automated tests must not call the real Zhipu API or consume quota.
- Follow red-green-refactor: every production behavior is preceded by a test that fails for the expected reason.

---

## File Map

- `backend/pom.xml`: dependency and Java version management.
- `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/*`: reproducible Maven execution.
- `backend/src/main/java/com/opspilot/ai/OpsPilotApplication.java`: Spring Boot entry point.
- `backend/src/main/java/com/opspilot/ai/chat/ChatGateway.java`: provider-independent model port.
- `backend/src/main/java/com/opspilot/ai/chat/ChatService.java`: chat use case.
- `backend/src/main/java/com/opspilot/ai/chat/SpringAiChatGateway.java`: Spring AI adapter.
- `backend/src/main/java/com/opspilot/ai/chat/UpstreamAiException.java`: safe upstream failure type.
- `backend/src/main/java/com/opspilot/ai/chat/api/ChatController.java`: HTTP endpoint.
- `backend/src/main/java/com/opspilot/ai/chat/api/ChatRequest.java`: validated request DTO.
- `backend/src/main/java/com/opspilot/ai/chat/api/ChatResponse.java`: response DTO.
- `backend/src/main/java/com/opspilot/ai/common/api/ApiError.java`: safe error payload.
- `backend/src/main/java/com/opspilot/ai/common/api/GlobalExceptionHandler.java`: HTTP error mapping.
- `backend/src/main/resources/application.yml`: non-secret provider configuration.
- `backend/src/test/**`: unit, controller, and context tests.
- `.env.example`: environment variable example without a secret.
- `.gitignore`: excludes IDE, build, and secret files.
- `README.md`: setup, test, run, and manual verification instructions.

### Task 1: Reproducible Spring Boot Skeleton

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/mvnw`
- Create: `backend/mvnw.cmd`
- Create: `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/src/main/java/com/opspilot/ai/OpsPilotApplication.java`
- Test: `backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**Interfaces:**
- Consumes: JDK 21 and Maven Central.
- Produces: bootable `com.opspilot.ai.OpsPilotApplication` and `backend/mvnw.cmd test`.

- [ ] **Step 1: Generate only the build wrapper and empty application structure**

Download a Maven project from Spring Initializr with Java 21, Boot 3.5.x, group `com.opspilot`, artifact `backend`, package `com.opspilot.ai`, and dependencies `web,validation`. Keep the generated Maven Wrapper. Add the Spring AI 1.1.8 BOM and `spring-ai-starter-model-openai` dependency to `pom.xml`.

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

Run: `cd backend; .\mvnw.cmd -Dtest=OpsPilotApplicationTests test`

Expected: compilation or context bootstrap failure because `OpsPilotApplication` does not exist.

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

Run: `cd backend; .\mvnw.cmd -Dtest=OpsPilotApplicationTests test`

Expected: `BUILD SUCCESS`, one passing test, and no real model call.

- [ ] **Step 6: Commit the skeleton**

```powershell
git add -- backend/pom.xml backend/mvnw backend/mvnw.cmd backend/.mvn backend/src/main/java/com/opspilot/ai/OpsPilotApplication.java backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "build: create Spring Boot backend skeleton"
```

### Task 2: Provider-Independent Chat Use Case

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/chat/ChatGateway.java`
- Create: `backend/src/main/java/com/opspilot/ai/chat/ChatService.java`
- Test: `backend/src/test/java/com/opspilot/ai/chat/ChatServiceTests.java`

**Interfaces:**
- Consumes: none beyond the JDK.
- Produces: `ChatGateway#generate(String): String` and `ChatService#chat(String): String`.

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

Run: `cd backend; .\mvnw.cmd -Dtest=ChatServiceTests test`

Expected: test compilation fails because `ChatGateway` and `ChatService` do not exist.

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

Run: `cd backend; .\mvnw.cmd -Dtest=ChatServiceTests test`

Expected: `BUILD SUCCESS` with one passing test.

- [ ] **Step 5: Commit the use case**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/chat/ChatGateway.java backend/src/main/java/com/opspilot/ai/chat/ChatService.java backend/src/test/java/com/opspilot/ai/chat/ChatServiceTests.java
git commit -m "feat: add provider-independent chat service"
```

### Task 3: Validated HTTP Chat Endpoint

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/chat/api/ChatRequest.java`
- Create: `backend/src/main/java/com/opspilot/ai/chat/api/ChatResponse.java`
- Create: `backend/src/main/java/com/opspilot/ai/chat/api/ChatController.java`
- Test: `backend/src/test/java/com/opspilot/ai/chat/api/ChatControllerTests.java`

**Interfaces:**
- Consumes: `ChatService#chat(String): String`.
- Produces: `POST /api/chat`, request `ChatRequest(String message)`, response `ChatResponse(String content)`.

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

Run: `cd backend; .\mvnw.cmd -Dtest=ChatControllerTests test`

Expected: compilation fails because the API records and controller do not exist.

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

Run: `cd backend; .\mvnw.cmd -Dtest=ChatControllerTests test`

Expected: `BUILD SUCCESS` with two passing tests.

- [ ] **Step 5: Commit the endpoint**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/chat/api backend/src/test/java/com/opspilot/ai/chat/api
git commit -m "feat: expose validated chat endpoint"
```

### Task 4: Spring AI Adapter and Safe Upstream Errors

**Files:**
- Create: `backend/src/main/java/com/opspilot/ai/chat/UpstreamAiException.java`
- Create: `backend/src/main/java/com/opspilot/ai/chat/SpringAiChatGateway.java`
- Create: `backend/src/main/java/com/opspilot/ai/common/api/ApiError.java`
- Create: `backend/src/main/java/com/opspilot/ai/common/api/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/opspilot/ai/chat/SpringAiChatGatewayTests.java`
- Test: `backend/src/test/java/com/opspilot/ai/common/api/GlobalExceptionHandlerTests.java`

**Interfaces:**
- Consumes: Spring AI `ChatClient.Builder` and `ChatGateway#generate(String)`.
- Produces: Spring bean implementing `ChatGateway`; `UpstreamAiException`; HTTP 502 payload `ApiError(String code, String message)`.

- [ ] **Step 1: Write a failing adapter error test**

Use Spring AI's testable `ChatModel` interface to build a `ChatClient` whose model throws, then assert that `SpringAiChatGateway#generate` throws `UpstreamAiException` with the safe message `AI service is unavailable`. Do not assert against or expose the original provider message.

```java
assertThatThrownBy(() -> gateway.generate("hello"))
    .isInstanceOf(UpstreamAiException.class)
    .hasMessage("AI service is unavailable");
```

- [ ] **Step 2: Run the adapter test and verify RED**

Run: `cd backend; .\mvnw.cmd -Dtest=SpringAiChatGatewayTests test`

Expected: compilation fails because the adapter and exception do not exist.

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

Run: `cd backend; .\mvnw.cmd -Dtest=SpringAiChatGatewayTests test`

Expected: `BUILD SUCCESS`; the original model exception is preserved only as the cause.

- [ ] **Step 5: Write the failing HTTP 502 test**

Build standalone MockMvc with a `ChatGateway` that throws `UpstreamAiException`, register `GlobalExceptionHandler`, and assert:

```java
.andExpect(status().isBadGateway())
.andExpect(jsonPath("$.code").value("UPSTREAM_AI_ERROR"))
.andExpect(jsonPath("$.message").value("AI service is unavailable"));
```

- [ ] **Step 6: Run the handler test and verify RED**

Run: `cd backend; .\mvnw.cmd -Dtest=GlobalExceptionHandlerTests test`

Expected: compilation fails because `ApiError` and `GlobalExceptionHandler` do not exist.

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

Run: `cd backend; .\mvnw.cmd -Dtest=SpringAiChatGatewayTests,GlobalExceptionHandlerTests,ChatControllerTests test`

Expected: `BUILD SUCCESS`; all adapter, handler, and controller tests pass.

- [ ] **Step 9: Commit the adapter and error mapping**

```powershell
git add -- backend/src/main/java/com/opspilot/ai/chat/SpringAiChatGateway.java backend/src/main/java/com/opspilot/ai/chat/UpstreamAiException.java backend/src/main/java/com/opspilot/ai/common/api backend/src/test/java/com/opspilot/ai/chat/SpringAiChatGatewayTests.java backend/src/test/java/com/opspilot/ai/common/api/GlobalExceptionHandlerTests.java
git commit -m "feat: connect chat service through Spring AI"
```

### Task 5: Secure Configuration, Documentation, and Real Verification

**Files:**
- Create: `backend/src/main/resources/application.yml`
- Create: `.env.example`
- Create: `.gitignore`
- Create: `README.md`
- Modify: `backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java`

**Interfaces:**
- Consumes: environment variable `ZHIPU_API_KEY`.
- Produces: documented local run workflow and configured `glm-4.7` client.

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

Retain `@SpringBootTest(properties = "spring.ai.model.chat=none")` and add a test-only `ChatGateway` bean if the application context requires one. The bean returns `test response` and is marked `@Primary`.

- [ ] **Step 4: Document exact learning workflow**

`README.md` must explain prerequisites, module boundaries, `ZHIPU_API_KEY` setup, test command, run command, curl/PowerShell request, expected response, and why tests use a fake gateway.

PowerShell setup and run example:

```powershell
$env:ZHIPU_API_KEY='your-real-key'
cd backend
.\mvnw.cmd spring-boot:run
```

Manual request:

```powershell
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/chat' `
  -ContentType 'application/json' `
  -Body '{"message":"请用一句话介绍 OpsPilot AI"}'
```

- [ ] **Step 5: Run the full automated suite**

Run: `cd backend; .\mvnw.cmd test`

Expected: `BUILD SUCCESS`; no test reads `ZHIPU_API_KEY` or calls the network.

- [ ] **Step 6: Scan for leaked secrets and unwanted files**

Run:

```powershell
git status --short
git grep -n -I -E 'Bearer [A-Za-z0-9._-]+|ZHIPU_API_KEY=[^r]' -- ':!docs/superpowers/**'
```

Expected: no real key matches; `.env` and `.idea/` are not staged.

- [ ] **Step 7: Perform one authorized real-model verification**

With `ZHIPU_API_KEY` set only in the current terminal, run the application and call `POST /api/chat`. Expected: HTTP 200 with non-empty `content`. Do not copy the key into commands that will be committed, logs, screenshots, or chat messages.

- [ ] **Step 8: Commit configuration and learning documentation**

```powershell
git add -- .gitignore .env.example README.md backend/src/main/resources/application.yml backend/src/test/java/com/opspilot/ai/OpsPilotApplicationTests.java
git commit -m "docs: add secure setup and verification guide"
```

- [ ] **Step 9: Final verification**

Run:

```powershell
cd backend
.\mvnw.cmd clean test
git status --short
```

Expected: `BUILD SUCCESS`; only previously existing, intentionally untracked project material may remain outside the committed milestone.

## Official References

- Spring AI compatibility: https://github.com/spring-projects/spring-ai
- Spring AI OpenAI chat configuration: https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html
- Zhipu OpenAI compatibility: https://docs.bigmodel.cn/cn/guide/develop/openai/introduction
- GLM-4.7 model: https://docs.bigmodel.cn/cn/guide/models/text/glm-4.7
