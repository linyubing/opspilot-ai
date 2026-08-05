# 对话接口 UTF-8 响应兼容实施计划

> **供智能体执行：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，逐项执行本计划。所有步骤使用复选框跟踪。

**目标：** 让 `POST /api/chat` 明确声明 UTF-8 JSON 响应，使 Windows PowerShell 5.1 能直接正确显示中文。

**架构：** 保持 Controller、Service、Gateway 和响应 DTO 的现有边界不变。只在控制器映射上声明带 UTF-8 字符集的 JSON 媒体类型，并用 MockMvc 测试锁定响应头与中文响应内容。

**技术栈：** Java 21、Spring Boot 3.5.14、Spring MVC、JUnit 5、MockMvc、AssertJ、Maven Wrapper。

## 全局约束

- 不改变 `ChatRequest` 或 `ChatResponse` 的 JSON 字段。
- 不改变智谱 API 地址、模型或认证配置。
- 不增加依赖，不实现流式响应。
- 测试和提交说明使用中文描述，Java 技术标识符保留英文。

---

### 任务 1：声明并验证 UTF-8 JSON 响应

**文件：**

- 修改：`backend/src/test/java/com/opspilot/ai/chat/api/ChatControllerTests.java`
- 修改：`backend/src/main/java/com/opspilot/ai/chat/api/ChatController.java`

**接口：**

- 输入：`POST /api/chat`，请求体为 `{"message":"请用一句话介绍 OpsPilot AI"}`。
- 输出：HTTP 200，`Content-Type` 与 `application/json;charset=UTF-8` 兼容，响应体保持 `{"content":"reply to: hello"}`。

- [ ] **步骤 1：编写失败测试**

在 `ChatControllerTests` 的正常请求测试中增加响应类型断言：

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "message":"hello"
                        }
                        """))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json;charset=UTF-8"))
        .andExpect(jsonPath("$.content").value("reply to: hello"));
```

- [ ] **步骤 2：运行测试并确认按预期失败**

运行：

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd -Dtest=ChatControllerTests test
```

预期：测试失败，实际响应类型缺少 `charset=UTF-8`；不能因编译错误或其他断言失败。

- [ ] **步骤 3：实现最小修改**

修改 `ChatController.chat` 的映射：

```java
@PostMapping(produces = "application/json;charset=UTF-8")
public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
    String content = service.chat(request.message());
    return new ChatResponse(content);
}
```

- [ ] **步骤 4：运行控制器测试并确认通过**

运行：

```powershell
.\mvnw.cmd -Dtest=ChatControllerTests test
```

预期：`ChatControllerTests` 的 2 个测试全部通过。

- [ ] **步骤 5：运行全部测试**

运行：

```powershell
.\mvnw.cmd test
```

预期：全部测试通过，失败数和错误数均为 0。

- [ ] **步骤 6：人工验证真实接口**

重启后端后执行：

```powershell
$body = @{ message = "请用一句话介绍 OpsPilot AI" } | ConvertTo-Json
$response = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/chat" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body))
$response.content
```

预期：PowerShell 直接显示正常中文，不需要二次编码转换。

- [ ] **步骤 7：提交代码**

```powershell
git add backend/src/main/java/com/opspilot/ai/chat/api/ChatController.java `
        backend/src/test/java/com/opspilot/ai/chat/api/ChatControllerTests.java
git commit -m "fix: 修复对话接口中文响应乱码"
```
