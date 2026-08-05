# OpsPilot AI 首个后端对话里程碑设计

## 1. 目标

创建一个便于学习和扩展的 Spring Boot 后端骨架，通过 Spring AI 调用智谱开放平台的 `glm-4.7` 模型，并提供一个同步对话接口。

本里程碑用于验证以下最短链路：

```text
HTTP 请求 -> Controller -> Service -> Spring AI ChatClient -> 智谱 GLM-4.7
```

## 2. 范围

### 包含

- 初始化 Git 仓库。
- 在 `backend` 目录创建 Java 21、Spring Boot 和 Maven Wrapper 项目。
- 引入 Spring Web、Validation、Spring AI OpenAI Starter 和测试依赖。
- 使用智谱普通开放平台 API 和模型 `glm-4.7`。
- 提供同步接口 `POST /api/chat`。
- 校验用户消息不能为空。
- 使用统一 JSON 请求与响应。
- 使用自动化测试验证接口契约、参数校验和服务行为。
- 通过环境变量注入 API Key。

### 不包含

- 流式响应。
- 多轮对话和持久化。
- RAG、向量数据库、文档上传。
- Agent、工具调用和 MCP。
- 前端、用户登录和权限。
- Docker 和外部数据库。

## 3. 技术方案

选择 Spring AI 的 OpenAI 兼容客户端接入智谱平台。该方案符合主项目的 Spring AI 技术路线，并能通过配置替换模型服务商。

连接配置：

- Base URL：`https://open.bigmodel.cn/api/paas/v4`
- 模型：`glm-4.7`
- API Key 环境变量：`ZHIPU_API_KEY`

不采用智谱专用 Java SDK，以避免首个业务模块绑定单一供应商；不手写 HTTP 客户端，以免在学习初期承担不必要的协议解析和流式处理代码。

## 4. 模块与职责

### ChatController

- 接收和校验 HTTP 请求。
- 调用 `ChatService`。
- 返回稳定的 JSON 接口结构。
- 不包含模型调用细节。

### ChatService

- 定义对话用例的业务边界。
- 接收用户消息并返回模型最终文本。
- 依赖 Spring AI `ChatClient`，不依赖 HTTP 层。

### 配置层

- 通过 Spring Boot 配置创建和注入模型客户端。
- 从环境变量读取 API Key。
- 集中配置 Base URL 和模型名。

### API DTO

- `ChatRequest`：包含非空 `message`。
- `ChatResponse`：包含 `content`。

## 5. API 契约

请求：

```http
POST /api/chat
Content-Type: application/json
```

```json
{
  "message": "请介绍 OpsPilot AI"
}
```

成功响应：

```json
{
  "content": "模型生成的回答"
}
```

空消息或仅包含空白字符时返回 HTTP `400 Bad Request`。模型调用失败时返回 HTTP `502 Bad Gateway`，响应中不泄露 API Key、上游原始响应或内部堆栈。

## 6. 错误处理与安全

- 请求参数由 Jakarta Validation 校验。
- 使用统一异常处理器映射输入错误和上游模型错误。
- API Key 仅从 `ZHIPU_API_KEY` 读取。
- 仓库只提供 `.env.example`，不保存真实密钥。
- 日志不记录 API Key，也不记录完整鉴权请求头。
- 首版配置有限次数重试和合理超时，避免请求无限等待。

## 7. 测试策略

遵循测试先行：先写一个会因功能不存在而失败的测试，再实现最少代码使其通过。

- Controller 测试：正常请求返回约定 JSON。
- Controller 测试：空消息返回 `400`。
- Service 测试：用户消息被传递给模型客户端并返回生成文本。
- 应用上下文测试：在测试配置中使用替代模型，避免测试依赖真实 API Key 和网络。
- 手动集成验证：使用个人环境变量调用一次真实 `glm-4.7`。

自动化测试默认不消耗智谱额度，也不受网络波动影响。

## 8. 验收标准

1. `backend/mvnw.cmd test` 全部通过。
2. 配置 `ZHIPU_API_KEY` 后，应用可以启动。
3. 正常请求可以获得 `glm-4.7` 返回的文本。
4. 空消息请求返回 HTTP 400。
5. 上游调用失败时返回 HTTP 502，且不泄露敏感信息。
6. 仓库中不存在真实 API Key。
7. 学习者能够说明 Controller、Service、ChatClient 和配置层的职责。

## 9. 官方参考

- 智谱 GLM-4.7：https://docs.bigmodel.cn/cn/guide/models/text/glm-4.7
- 智谱 OpenAI API 兼容说明：https://docs.bigmodel.cn/cn/guide/develop/openai/introduction
- Spring AI OpenAI Chat：https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html
