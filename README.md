# OpsPilot AI

OpsPilot AI 是一个用于学习 Java 后端与 AI 应用开发的项目。

当前阶段已经实现基于 Spring Boot、Spring AI 和智谱 `glm-4.7` 的同步对话接口，并包含参数校验、安全日志和上游异常处理。

## 技术栈

- Java 21
- Spring Boot 3.5.14
- Spring AI 1.1.8
- Maven Wrapper
- 智谱开放平台 `glm-4.7`
- JUnit 5
- AssertJ
- MockMvc

## 当前功能

- 提供 `POST /api/chat` 对话接口。
- 校验用户消息不能为空。
- 通过 Spring AI 调用智谱 `glm-4.7`。
- 使用环境变量读取 API Key。
- 记录调用长度、耗时和异常类型。
- 不在日志中记录 API Key、完整问题和完整回答。
- 上游 AI 调用失败时返回 HTTP 502。
- 自动化测试不请求真实模型，不消耗模型额度。

## 项目结构

```text
demo-ai
├─ backend
│  ├─ src/main/java/com/opspilot/ai
│  │  ├─ OpsPilotApplication.java        # Spring Boot 启动入口
│  │  └─ chat
│  │     ├─ ChatGateway.java              # AI 模型调用接口
│  │     ├─ ChatService.java              # 对话业务服务
│  │     ├─ SpringAiChatGateway.java      # Spring AI 实现
│  │     ├─ UpstreamAiException.java      # 上游 AI 异常
│  │     └─ api
│  │        ├─ ChatController.java        # HTTP 对话接口
│  │        ├─ ChatRequest.java           # 请求数据
│  │        ├─ ChatResponse.java          # 响应数据
│  │        ├─ ApiError.java              # 错误响应数据
│  │        └─ GlobalExceptionHandler.java# 全局异常处理
│  └─ pom.xml
├─ docs                                  # 设计与实施计划
├─ .env.example                          # 环境变量示例
└─ README.md
```

## 环境要求

请先安装：

- JDK 21
- Git
- 可访问 Maven Central 和智谱开放平台的网络

项目包含 Maven Wrapper，因此不要求全局安装 Maven。

验证 Java：

```powershell
java -version
```

预期显示 Java 21。

## 配置智谱 API Key

在智谱开放平台创建普通 API Key，并保存为 Windows 用户环境变量。

```powershell
# 以隐藏方式读取 Key，避免直接显示在终端中
$secureKey = Read-Host "请输入智谱 API Key" -AsSecureString

# 将 SecureString 转换为普通字符串，仅用于写入环境变量
$key = [System.Net.NetworkCredential]::new("", $secureKey).Password

# 保存到当前 Windows 用户的环境变量
[Environment]::SetEnvironmentVariable("ZHIPU_API_KEY", $key, "User")

# 清理当前 PowerShell 中的临时变量
Remove-Variable secureKey, key
```

设置完成后，请关闭并重新打开 PowerShell 或 IntelliJ。

检查变量是否存在，但不要打印 Key：

```powershell
if ($env:ZHIPU_API_KEY) {
    "已经读取到 ZHIPU_API_KEY"
} else {
    "没有读取到 ZHIPU_API_KEY"
}
```

不要把真实 Key 写入：

- `application.yaml`
- `.env.example`
- README
- Git 提交
- 聊天或截图

## 运行测试

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd test
```

自动化测试使用模拟的 `ChatGateway` 或 `ChatModel`，不会调用真实智谱 API，也不会消耗额度。

## 启动项目

```powershell
cd D:\workFile\demo-ai\backend
.\mvnw.cmd spring-boot:run
```

启动成功后，服务地址为：

```text
http://localhost:8080
```

## 调用对话接口

打开另一个 PowerShell：

```powershell
# 构造请求 JSON
$body = @{
    message = "请用一句话介绍 OpsPilot AI"
} | ConvertTo-Json

# 调用本地对话接口，并保存返回结果
$response = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/chat" `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($body))

# 显示模型回答
$response.content
```

正常响应示例：

```json
{
  "content": "OpsPilot AI 是一个基于大语言模型的智能运维助手。"
}
```

## 错误响应

当上游 AI 服务不可用时，接口返回 HTTP 502：

```json
{
  "code": "AI_SERVICE_UNAVAILABLE",
  "message": "AI 服务暂时不可用，请稍后重试"
}
```

HTTP 502 表示 OpsPilot AI 本身能够接收请求，但它依赖的上游 AI 服务调用失败。

## 安全日志

成功调用日志示例：

```text
AI 对话调用完成，问题长度=19，回答长度=69，耗时=20668毫秒
```

失败调用日志示例：

```text
AI 对话调用失败，问题长度=4，耗时=20毫秒，异常类型=IllegalStateException
```

日志不会打印 API Key、完整问题或完整模型回答。

## 黄金历史方向回测

历史回测使用数据库中的真实黄金价格、实际利率和美元指数，按历史截止日重建研究快照。回测数据与实时预测表完全隔离。

### 1. 创建回测任务

下面先创建 5 条样本，创建过程不会调用大模型：

```powershell
$task = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/research/gold/backtests?samples=5"

$task | Format-List
$id = $task.id
```

`samples` 允许范围为 `1` 到 `120`。正式跑 60 条前，建议先用 5 条验证完整流程和模型额度。

### 2. 启动后台回测

下面的接口会针对每个尚未完成的历史日期调用一次 GLM，可能消耗 API 额度：

```powershell
$running = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/api/research/gold/backtests/$id/run"

$running | Format-List
```

相同任务重复启动不会重复提交；失败后再次启动时，已经保存的日期不会重复调用模型。

### 3. 查询任务进度

```powershell
$status = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8080/api/research/gold/backtests/$id"

$status | Format-List
```

重点查看 `status`、`completedCount`、`hitCount`、`failedCount` 和 `lastError`。

### 4. 查看逐日涨跌结果

```powershell
$results = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8080/api/research/gold/backtests/$id/results?limit=60"

$results | Format-Table `
    asOfDate, predictedDirection, targetDate, actualDirection, hit
```

接口不会返回完整提示词或模型原始响应。

### 5. 查看准确率评估

```powershell
$evaluation = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8080/api/research/gold/backtests/$id/evaluation"

$evaluation | Format-List
```

`accuracy` 是总体准确率，`rolling20Accuracy` 是最近 20 条准确率，`neutralBaselineAccuracy` 是全部猜中性时的基线准确率。只有明显高于简单基线，模型预测才具有进一步研究价值。

## GitHub

项目地址：

https://github.com/linyubing/opspilot-ai
